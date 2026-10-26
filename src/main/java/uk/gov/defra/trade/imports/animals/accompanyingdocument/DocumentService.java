package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

/**
 * Business logic for accompanying documents: recording an upload session, taking the document that
 * comes through it on, recording the scan result, and reading or removing what was stored.
 *
 * <p>Why a session and a document are separate records is in {@code docs/design-notes.md}.
 */
@Service
@Slf4j
public class DocumentService {

  private final AccompanyingDocumentRepository accompanyingDocumentRepository;
  private final UploadSessionRepository uploadSessionRepository;

  public DocumentService(
      AccompanyingDocumentRepository accompanyingDocumentRepository,
      UploadSessionRepository uploadSessionRepository) {
    this.accompanyingDocumentRepository = accompanyingDocumentRepository;
    this.uploadSessionRepository = uploadSessionRepository;
  }

  /**
   * Records an upload session the caller has opened with cdp-uploader, so that whatever arrives
   * through it can be matched to its notification. No document is written here: a session is opened
   * every time the upload page is painted, and most are never uploaded through.
   *
   * <p>Several open sessions per notification are deliberately allowed — a trader may start a
   * second document while the first is still being checked.
   *
   * @param notificationRef the parent notification reference number
   * @param request         the upload session's identifiers
   * @return the recorded session
   * @throws ConflictException if either identifier is already recorded
   */
  public UploadSession registerUpload(String notificationRef, RegisterUploadRequest request) {
    UploadSession session = UploadSession.builder()
        .notificationReferenceNumber(notificationRef)
        .uploadId(request.uploadId())
        .correlationId(request.correlationId())
        .build();

    return saveOrThrowOnDuplicate(session, notificationRef);
  }

  /**
   * Returns the upload session a notification opened under the given correlation handle, which is
   * how the uploading journey recovers the session it opened when the browser comes back to it.
   *
   * <p>Scoped to the notification deliberately: the handle alone must not be enough to reach a
   * session opened by someone else's notification.
   *
   * @param notificationRef the parent notification reference number
   * @param correlationId   the handle the session was opened under
   * @return the session
   * @throws NotFoundException if that notification has no session under the handle
   */
  public UploadSession findSession(String notificationRef, String correlationId) {
    return uploadSessionRepository
        .findByNotificationReferenceNumberAndCorrelationId(notificationRef, correlationId)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found for notification " + notificationRef
                + " with correlationId: " + correlationId));
  }

  /**
   * Takes a trader's validated answers on as the notification's document, which is the point at
   * which a document is known to exist. Only claimed documents are published on a notification's
   * events, so a file the trader walked away from never reaches one.
   *
   * <p>The scan callback may land first, so the document is written where one is already waiting
   * and created where it is not. Claiming twice replaces the details rather than adding a second
   * document.
   *
   * @param uploadId the upload session identifier
   * @param request  the document details the trader gave
   * @return the claimed document
   * @throws NotFoundException if no session was ever opened under {@code uploadId}
   */
  @Transactional
  public AccompanyingDocument claimUpload(String uploadId, ClaimUploadRequest request) {
    UploadSession session = uploadSessionRepository.findByUploadId(uploadId)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found with uploadId: " + uploadId));
    AccompanyingDocument document = existingOrNewDocumentFor(session);

    document.setDocumentType(request.documentType());
    document.setDocumentReference(request.documentReference());
    document.setDateOfIssue(request.dateOfIssue());
    document.setClaimed(true);

    AccompanyingDocument claimed = accompanyingDocumentRepository.save(document);
    log.info("Claimed uploadId {} correlationId {} for notification {}", uploadId,
        session.getCorrelationId(),
        session.getNotificationReferenceNumber());
    return claimed;
  }

  /**
   * Records a cdp-uploader scan result against the document the session opened under
   * {@code metadata.correlationId}, creating the document when neither the claim nor an earlier
   * callback has written it yet.
   *
   * @param uploadId the upload session identifier from the callback path, used only in logs
   * @param payload  the callback payload
   * @throws BadRequestException if the payload metadata does not include a correlationId
   * @throws NotFoundException   if no session was opened under that correlationId
   */
  @Transactional
  public void handleScanResult(String uploadId, CdpScanResultPayload payload) {
    String correlationId = extractCorrelationId(payload);
    UploadSession session = uploadSessionRepository.findByCorrelationId(correlationId)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found with correlationId: " + correlationId));
    AccompanyingDocument document = existingOrNewDocumentFor(session);
    boolean documentExisted = document.getId() != null;

    document.setFiles(mapUploadedFiles(payload));
    document.setScanStatus(resolveScanStatus(payload));

    accompanyingDocumentRepository.save(document);
    log.info(
        "Scan result for uploadId {} correlationId {} notification {}: scanStatus {}, document {}",
        document.getUploadId(),
        document.getCorrelationId(),
        document.getNotificationReferenceNumber(),
        document.getScanStatus(),
        documentExisted ? "already existed" : "created by this callback");
  }

  /**
   * Deletes the accompanying document with the given upload ID, and the session it was uploaded
   * through, so a scan result arriving afterwards cannot bring the document back.
   *
   * @param uploadId the upload session identifier
   * @throws NotFoundException if not found
   */
  public void deleteByUploadId(String uploadId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    accompanyingDocumentRepository.delete(document);
    uploadSessionRepository.findByUploadId(uploadId).ifPresent(uploadSessionRepository::delete);
    log.info("Deleted AccompanyingDocument and its session, uploadId {} correlationId {}",
        uploadId, document.getCorrelationId());
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
    uploadSessionRepository.deleteAllByNotificationReferenceNumberIn(referenceNumbers);
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

  /**
   * The document recorded for a session, creating an unsaved one when neither the claim nor the
   * scan callback has written it yet. Both resolve the same document by the session's correlation
   * handle, so the second to arrive adds to what the first wrote.
   */
  private AccompanyingDocument existingOrNewDocumentFor(UploadSession session) {
    return accompanyingDocumentRepository
        .findByCorrelationId(session.getCorrelationId())
        .orElseGet(() -> AccompanyingDocument.builder()
            .notificationReferenceNumber(session.getNotificationReferenceNumber())
            .uploadId(session.getUploadId())
            .correlationId(session.getCorrelationId())
            .scanStatus(ScanStatus.PENDING)
            .files(new ArrayList<>())
            .build());
  }

  private UploadSession saveOrThrowOnDuplicate(UploadSession session, String notificationRef) {
    try {
      UploadSession saved = uploadSessionRepository.save(session);
      log.info(
          "Registered uploadId {} correlationId {} for notification {}",
          session.getUploadId(),
          session.getCorrelationId(),
          notificationRef);
      return saved;
    } catch (DuplicateKeyException _) {
      log.warn(
          "Duplicate uploadId {} or correlationId {} for notification {}",
          session.getUploadId(),
          session.getCorrelationId(),
          notificationRef);
      throw new ConflictException(
          "An upload session already exists for uploadId " + session.getUploadId()
              + " or correlationId " + session.getCorrelationId());
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
