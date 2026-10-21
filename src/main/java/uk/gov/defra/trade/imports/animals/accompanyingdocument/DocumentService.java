package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

/**
 * Business logic for accompanying documents: registering an upload session, claiming its document
 * into a notification, recording the scan result, and reading or removing what was stored.
 */
@Service
@Slf4j
public class DocumentService {

  private final AccompanyingDocumentRepository accompanyingDocumentRepository;

  public DocumentService(AccompanyingDocumentRepository accompanyingDocumentRepository) {
    this.accompanyingDocumentRepository = accompanyingDocumentRepository;
  }

  /**
   * Records an upload session the caller has opened with cdp-uploader, so the scan callback has a
   * document to resolve when it arrives. The document carries no details yet and is unclaimed:
   * both arrive from {@link #claimUpload}, once the user has submitted the form and their answers
   * have been validated.
   *
   * <p>Several pending documents per notification are deliberately allowed — a user may add a
   * second document while the first is still being checked.
   *
   * @param notificationRef the parent notification reference number
   * @param request         the upload session's identifiers
   * @return the registered document
   * @throws ConflictException if either identifier is already registered
   */
  public AccompanyingDocument registerUpload(
      String notificationRef, RegisterUploadRequest request) {

    AccompanyingDocument document = AccompanyingDocument.builder()
        .notificationReferenceNumber(notificationRef)
        .uploadId(request.uploadId())
        .correlationId(request.correlationId())
        .scanStatus(ScanStatus.PENDING)
        .files(new ArrayList<>())
        .build();

    return saveOrThrowOnDuplicate(document, notificationRef);
  }

  /**
   * Attaches a user's validated answers to a registered upload and claims it for the notification.
   * Only claimed documents are published on a notification's events, so an upload the user walked
   * away from never reaches one.
   *
   * <p>Claiming an already-claimed document replaces its details with the ones supplied, so a
   * repeated request cannot produce a second document.
   *
   * @param uploadId the upload session identifier
   * @param request  the document details the user gave
   * @return the claimed document
   * @throws NotFoundException if no document is registered under {@code uploadId}
   */
  public AccompanyingDocument claimUpload(String uploadId, ClaimUploadRequest request) {
    AccompanyingDocument document = findByUploadId(uploadId);

    document.setDocumentType(request.documentType());
    document.setDocumentReference(request.documentReference());
    document.setDateOfIssue(request.dateOfIssue());
    document.setClaimed(true);

    AccompanyingDocument claimed = accompanyingDocumentRepository.save(document);
    log.info("Claimed uploadId {} for notification {}", uploadId,
        document.getNotificationReferenceNumber());
    return claimed;
  }

  /**
   * Processes a cdp-uploader scan result callback, updating the document's scan status and file
   * list. The document is resolved via {@code metadata.correlationId} — cdp-uploader echoes the
   * metadata map back verbatim and does not include {@code uploadId} in the callback body, so a
   * backend-minted correlationId is the only safe disambiguator when multiple PENDING uploads
   * share a notification reference.
   *
   * @param uploadId the upload session identifier from the callback path; informational only
   *                 (kept for log breadcrumbs — resolution is via {@code metadata.correlationId})
   * @param payload  the callback payload from cdp-uploader
   * @throws BadRequestException if the payload metadata does not include a correlationId
   * @throws NotFoundException   if no document with the given correlationId exists
   */
  public void handleScanResult(String uploadId, CdpScanResultPayload payload) {
    String correlationId = extractCorrelationId(payload);
    AccompanyingDocument document = findByCorrelationId(correlationId);

    document.setFiles(mapUploadedFiles(payload));
    document.setScanStatus(resolveScanStatus(payload));

    accompanyingDocumentRepository.save(document);
    log.info("Updated uploadId {} scanStatus to {}", document.getUploadId(), document.getScanStatus());
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

  private AccompanyingDocument saveOrThrowOnDuplicate(
      AccompanyingDocument document, String notificationRef) {
    try {
      AccompanyingDocument saved = accompanyingDocumentRepository.save(document);
      log.info(
          "Registered uploadId {} for notification {}",
          document.getUploadId(),
          notificationRef);
      return saved;
    } catch (DuplicateKeyException _) {
      log.warn(
          "Duplicate uploadId {} or correlationId {} for notification {}",
          document.getUploadId(),
          document.getCorrelationId(),
          notificationRef);
      throw new ConflictException(
          "Upload session with id " + document.getUploadId() + " already exists");
    }
  }

  private static String extractCorrelationId(CdpScanResultPayload payload) {
    String correlationId = payload.metadata().get("correlationId");
    if (correlationId == null || correlationId.isBlank()) {
      throw new BadRequestException(
          "Scan callback missing required correlationId in metadata");
    }
    return correlationId;
  }

  private AccompanyingDocument findByCorrelationId(String correlationId) {
    return accompanyingDocumentRepository
        .findByCorrelationId(correlationId)
        .orElseThrow(() -> new NotFoundException(
            "No accompanying document found with correlationId: " + correlationId));
  }

  private static List<UploadedFile> mapUploadedFiles(CdpScanResultPayload payload) {
    List<UploadedFile> files = new ArrayList<>();
    if (payload.form() != null) {
      for (CdpScanResultFile entry : payload.form().getFiles().values()) {
        files.add(UploadedFile.from(entry));
      }
    }
    return files;
  }

  /**
   * Fail-closed: treat a missing or non-zero rejected count as REJECTED to avoid silently
   * accepting a file when cdp-uploader omits the field.
   */
  private static ScanStatus resolveScanStatus(CdpScanResultPayload payload) {
    Integer rejectedCount = payload.numberOfRejectedFiles();
    return rejectedCount != null && rejectedCount == 0
        ? ScanStatus.COMPLETE
        : ScanStatus.REJECTED;
  }
}
