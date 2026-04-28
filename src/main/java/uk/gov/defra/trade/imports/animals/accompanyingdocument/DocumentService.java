package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.cdp.CdpUploaderClient;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
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
   * <p>This method is <strong>not idempotent</strong>. cdp-uploader is always called to create a
   * new upload session. The returned upload ID is then persisted; if a record with the same upload
   * ID already exists (e.g. due to a concurrent retry), the unique-index constraint causes a
   * {@link org.springframework.dao.DuplicateKeyException} which is re-thrown as a
   * {@link uk.gov.defra.trade.imports.animals.exceptions.ConflictException}. In that scenario the
   * cdp-uploader session that was just created becomes orphaned — no corresponding database record
   * is saved for it.
   *
   * @param notificationRef the parent notification reference number
   * @param request         the document metadata supplied by the user
   * @param redirectUrl     the URL to redirect the user to after they complete the upload form
   * @return the upload session details (upload ID and upload URL)
   */
  public DocumentUploadResponse initiate(
      String notificationRef, DocumentUploadRequest request, String redirectUrl) {

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
      throw new ConflictException(
          "Upload session with id " + response.uploadId() + " already exists");
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
    // cdp-uploader does not include uploadId in the callback payload body.
    // When the path variable is the "pending" placeholder used at initiation time,
    // look up the document by notification reference from the callback metadata instead.
    AccompanyingDocument document;
    if ("pending".equals(uploadId)
        && payload.metadata() != null
        && payload.metadata().containsKey("notificationReferenceNumber")) {
      String notificationRef = payload.metadata().get("notificationReferenceNumber");
      log.info("Resolving pending callback via notificationRef={}", notificationRef);
      document = accompanyingDocumentRepository
          .findFirstByNotificationReferenceNumberAndScanStatus(notificationRef, ScanStatus.PENDING)
          .orElseThrow(() -> new NotFoundException(
              "No pending document found for notification: " + notificationRef));
    } else {
      document = findByUploadId(uploadId);
    }

    List<UploadedFile> uploadedFiles = new ArrayList<>();
    if (payload.form() != null) {
      for (Map.Entry<String, CdpScanResultFile> entry : payload.form().getFiles().entrySet()) {
        uploadedFiles.add(UploadedFile.from(entry.getValue()));
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
    log.info("Updated uploadId {} scanStatus to {}", document.getUploadId(), scanStatus);
  }

  /**
   * Deletes the accompanying document with the given upload ID.
   *
   * @param uploadId the upload session identifier
   * @throws NotFoundException if not found
   */
  public void deleteByUploadId(String uploadId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    accompanyingDocumentRepository.delete(document);
    log.info("Deleted AccompanyingDocument with uploadId {}", uploadId);
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
   * Deletes all accompanying documents belonging to the given notification reference numbers.
   * Called as part of cascade deletion when notifications are removed.
   *
   * @param referenceNumbers the parent notification reference numbers whose documents should be deleted
   */
  public void deleteForNotificationRefs(List<String> referenceNumbers) {
    Objects.requireNonNull(referenceNumbers, "referenceNumbers must not be null");
    log.info("Cascade deleting accompanying documents for {} notification(s)", referenceNumbers.size());
    accompanyingDocumentRepository.deleteAllByNotificationReferenceNumberIn(referenceNumbers);
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
   * Returns the uploaded file for the given upload session.
   *
   * <p>The upload workflow enforces a one-file-per-session invariant, so {@code findFirst()} is
   * safe here. This method does not enforce that invariant structurally — it relies on the workflow
   * contract. Throws {@link NotFoundException} if the session has no file or if the file was
   * rejected (s3Key is null).
   *
   * @param uploadId the upload session identifier
   * @return the uploaded file record
   * @throws NotFoundException if the document has no file or the file was rejected
   */
  public UploadedFile findFile(String uploadId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    UploadedFile file = document.getFiles().stream()
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException(
                "No file found for uploadId: " + uploadId));
    if (file.s3Key() == null) {
      throw new NotFoundException(
          "File for uploadId: " + uploadId + " was rejected and is not available for download");
    }
    return file;
  }
}
