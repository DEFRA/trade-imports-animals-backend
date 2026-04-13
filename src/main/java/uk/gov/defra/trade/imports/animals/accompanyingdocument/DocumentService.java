package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

/**
 * Business logic for accompanying document uploads.
 *
 * <p>Orchestrates calls to the cdp-uploader service and persists the resulting
 * {@link AccompanyingDocument} entities.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

  private final AccompanyingDocumentRepository accompanyingDocumentRepository;
  private final CdpUploaderClient cdpUploaderClient;
  private final CdpConfig cdpConfig;

  /**
   * Initiates a new document upload session via cdp-uploader and persists a pending
   * {@link AccompanyingDocument}.
   *
   * <p>Idempotent: if a PENDING upload already exists for the same notification reference, the
   * existing upload details are returned without calling cdp-uploader again.
   *
   * @param notificationRef the parent notification reference number
   * @param request         the document metadata supplied by the user
   * @param redirectUrl     the URL to redirect the user to after they complete the upload form
   * @return the upload session details (upload ID and upload URL)
   */
  public DocumentUploadResponse initiate(
      String notificationRef, DocumentUploadRequest request, String redirectUrl) {

    // Idempotency: return existing PENDING upload if one already exists for this notification
    List<AccompanyingDocument> existing =
        accompanyingDocumentRepository.findAllByNotificationReferenceNumber(notificationRef);
    Optional<AccompanyingDocument> pending =
        existing.stream().filter(d -> d.getScanStatus() == ScanStatus.PENDING).findFirst();
    if (pending.isPresent()) {
      AccompanyingDocument doc = pending.get();
      log.info(
          "Returning existing PENDING upload {} for notification {}",
          doc.getUploadId(),
          notificationRef);
      return new DocumentUploadResponse(doc.getUploadId(), doc.getUploadUrl());
    }

    // Build callback URL from backend base URL
    // A temporary placeholder uploadId is used for the path; cdp-uploader will assign the real one
    // and return it in the response. We use a path template here because cdp-uploader uses the
    // uploadId it generates — the callback URL must contain the actual uploadId. We therefore
    // request initiation first and use the returned uploadId to construct the stored record.
    String callbackBase = cdpConfig.backend().baseUrl();

    Map<String, String> metadata = Map.of(
        "notificationReferenceNumber", notificationRef,
        "documentType", request.documentType() != null ? request.documentType().name() : "",
        "documentReference", request.documentReference() != null ? request.documentReference() : "");

    // The callback URL is POSTed to by cdp-uploader after scanning completes.
    // cdp-uploader validates the callback as a valid URI and posts to it verbatim — it does NOT
    // perform any template substitution. Because cdp-uploader assigns the uploadId and we need it
    // in the callback path, we call /initiate with a "pending" placeholder in the URL, then
    // immediately replace the placeholder with the real uploadId returned in the /initiate response.
    // The resolved URL is used for all downstream correlation.
    String callbackUrl = callbackBase + "/document-uploads/pending/scan-results";

    CdpUploaderInitiateRequest initiateRequest = new CdpUploaderInitiateRequest(
        redirectUrl,
        callbackUrl,
        cdpConfig.s3().documentsBucket(),
        notificationRef,
        cdpConfig.uploader().maxFileSize(),
        cdpConfig.uploader().mimeTypes(),
        metadata);

    log.info("Initiating cdp-uploader session for notification {}", notificationRef);
    CdpUploaderInitiateResponse response = cdpUploaderClient.initiate(initiateRequest);

    // Build the callback URL with the real uploadId for storing / logging
    String resolvedCallbackUrl =
        callbackBase + "/document-uploads/" + response.uploadId() + "/scan-results";
    log.debug("Resolved callback URL: {}", resolvedCallbackUrl);

    Instant dateOfIssueInstant = request.dateOfIssue() != null
        ? request.dateOfIssue().atStartOfDay(ZoneOffset.UTC).toInstant()
        : null;

    AccompanyingDocument document = AccompanyingDocument.builder()
        .notificationReferenceNumber(notificationRef)
        .uploadId(response.uploadId())
        .uploadUrl(response.uploadUrl())
        .documentType(request.documentType())
        .documentReference(request.documentReference())
        .dateOfIssue(dateOfIssueInstant)
        .scanStatus(ScanStatus.PENDING)
        .files(new ArrayList<>())
        .build();

    try {
      accompanyingDocumentRepository.save(document);
      log.info(
          "Saved AccompanyingDocument with uploadId {} for notification {}",
          response.uploadId(),
          notificationRef);
    } catch (DuplicateKeyException e) {
      log.warn(
          "Duplicate uploadId {} for notification {} — concurrent initiation race",
          response.uploadId(),
          notificationRef);
      throw new IllegalStateException(
          "Upload session with id " + response.uploadId() + " already exists", e);
    }

    return new DocumentUploadResponse(response.uploadId(), response.uploadUrl());
  }

  /**
   * Processes a cdp-uploader scan result callback, updating the document's scan status and file
   * list.
   *
   * @param uploadId the upload session identifier
   * @param payload  the callback payload from cdp-uploader
   * @throws NotFoundException if no document with the given upload ID exists
   */
  public void handleScanResult(String uploadId, CdpScanResultPayload payload) {
    AccompanyingDocument document = findByUploadId(uploadId);

    List<UploadedFile> uploadedFiles = new ArrayList<>();
    if (payload.form() != null) {
      for (Map.Entry<String, CdpScanResultFile> entry : payload.form().getFiles().entrySet()) {
        CdpScanResultFile f = entry.getValue();
        uploadedFiles.add(new UploadedFile(
            f.fileId(),
            f.filename(),
            f.contentType(),
            f.contentLength(),
            f.s3Key(),
            f.s3Bucket(),
            f.fileStatus(),
            f.checksumSha256(),
            f.detectedContentType(),
            f.hasError(),
            f.errorMessage()));
      }
    }

    document.setFiles(uploadedFiles);

    // Fail-closed: treat missing or non-zero rejected count as REJECTED to avoid
    // silently accepting a file when cdp-uploader omits the field.
    ScanStatus scanStatus = (payload.numberOfRejectedFiles() != null
        && payload.numberOfRejectedFiles() == 0)
        ? ScanStatus.COMPLETE
        : ScanStatus.REJECTED;
    document.setScanStatus(scanStatus);

    accompanyingDocumentRepository.save(document);
    log.info("Updated uploadId {} scanStatus to {}", uploadId, scanStatus);
  }

  /**
   * Returns all accompanying documents for the given notification reference.
   *
   * @param ref the notification reference number
   * @return list of documents, possibly empty
   */
  public List<AccompanyingDocument> findByNotificationRef(String ref) {
    return accompanyingDocumentRepository.findAllByNotificationReferenceNumber(ref);
  }

  /**
   * Returns the accompanying document with the given upload ID.
   *
   * @param uploadId the upload session identifier
   * @return the document
   * @throws NotFoundException if not found
   */
  public AccompanyingDocument findByUploadId(String uploadId) {
    return accompanyingDocumentRepository.findByUploadId(uploadId)
        .orElseThrow(
            () -> new NotFoundException("No accompanying document found with uploadId: " + uploadId));
  }

  /**
   * Returns the specific uploaded file within a document identified by {@code uploadId}.
   *
   * @param uploadId the upload session identifier
   * @param fileId   the file identifier
   * @return the uploaded file record
   * @throws NotFoundException if the document or file is not found
   */
  public UploadedFile findFile(String uploadId, String fileId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    UploadedFile file = document.getFiles().stream()
        .filter(f -> fileId.equals(f.fileId()))
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException(
                "No file found with fileId: " + fileId + " in uploadId: " + uploadId));
    if (file.s3Key() == null) {
      throw new NotFoundException(
          "File with fileId: " + fileId + " was rejected and is not available for download");
    }
    return file;
  }
}
