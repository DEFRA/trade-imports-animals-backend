package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import uk.gov.defra.trade.imports.animals.notification.NotificationExpiry;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;

/**
 * Business logic for accompanying documents: recording an upload session, taking the document that
 * comes through it on, recording the scan result, and reading or removing what was stored.
 */
@Service
@Slf4j
public class DocumentService {

  private final AccompanyingDocumentRepository accompanyingDocumentRepository;
  private final UploadSessionRepository uploadSessionRepository;
  private final NotificationRepository notificationRepository;

  public DocumentService(
      AccompanyingDocumentRepository accompanyingDocumentRepository,
      UploadSessionRepository uploadSessionRepository,
      NotificationRepository notificationRepository) {
    this.accompanyingDocumentRepository = accompanyingDocumentRepository;
    this.uploadSessionRepository = uploadSessionRepository;
    this.notificationRepository = notificationRepository;
  }

  /**
   * Records an upload session for a notification, which may already hold others. No document is
   * written.
   *
   * @param notificationReferenceNumber the parent notification reference number
   * @param request the upload session's identifiers
   * @return the recorded session
   * @throws ConflictException if either identifier is already recorded
   */
  public UploadSession registerUpload(
      String notificationReferenceNumber, RegisterUploadRequest request) {
    UploadSession session = UploadSession.builder()
        .notificationReferenceNumber(notificationReferenceNumber)
        .uploadId(request.uploadId())
        .correlationId(request.correlationId())
        .build();

    return saveSession(session);
  }

  /**
   * Returns the upload session a notification opened under the given correlationId.
   *
   * @param notificationReferenceNumber the parent notification reference number
   * @param correlationId the correlationId the session was opened under
   * @return the session
   * @throws NotFoundException if that notification has no session under the correlationId
   */
  public UploadSession findSession(String notificationReferenceNumber, String correlationId) {
    return uploadSessionRepository
        .findByNotificationReferenceNumberAndCorrelationId(
            notificationReferenceNumber, correlationId)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found for notification " + notificationReferenceNumber
                + " with correlationId: " + correlationId));
  }

  /**
   * Writes the given details onto the session's document and marks it claimed, creating the
   * document when none exists yet. Claiming again replaces the details.
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
   * Records a scan result against the document of the session opened under
   * {@code metadata.correlationId}, creating the document when none exists yet.
   *
   * @param payload the callback payload
   * @throws BadRequestException if the payload metadata does not include a correlationId
   * @throws NotFoundException   if no session was opened under that correlationId
   */
  @Transactional
  public void handleScanResult(CdpScanResultPayload payload) {
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
   * Deletes the accompanying document with the given upload ID and the session it was uploaded
   * through.
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
   * Returns all accompanying documents for the given notification reference number.
   *
   * @param notificationReferenceNumber the notification reference number
   * @return list of documents, possibly empty
   */
  public List<AccompanyingDocument> findByNotificationReferenceNumber(
      String notificationReferenceNumber) {
    return accompanyingDocumentRepository
        .findAllByNotificationReferenceNumber(notificationReferenceNumber);
  }

  /**
   * Deletes all accompanying documents and upload sessions belonging to the given notification
   * reference numbers.
   *
   * @param notificationReferenceNumbers the parent notification reference numbers whose documents
   *     and sessions should be deleted
   */
  public void deleteForNotificationReferenceNumbers(List<String> notificationReferenceNumbers) {
    Objects.requireNonNull(
        notificationReferenceNumbers, "notificationReferenceNumbers must not be null");
    log.info("Deleting accompanying documents and upload sessions for {} notification(s)",
        notificationReferenceNumbers.size());
    accompanyingDocumentRepository
        .deleteAllByNotificationReferenceNumberIn(notificationReferenceNumbers);
    uploadSessionRepository.deleteAllByNotificationReferenceNumberIn(notificationReferenceNumbers);
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

  public String notificationReferenceNumberForUploadId(String uploadId) {
    return uploadSessionRepository.findByUploadId(uploadId)
        .map(UploadSession::getNotificationReferenceNumber)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found with uploadId: " + uploadId));
  }

  /**
   * Returns the first uploaded file for the given upload session.
   *
   * @param uploadId the upload session identifier
   * @return the uploaded file record
   * @throws NotFoundException if no document exists for the uploadId, it has no file, or its file
   *     was rejected
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

  private AccompanyingDocument existingOrNewDocumentFor(UploadSession session) {
    return accompanyingDocumentRepository
        .findByCorrelationId(session.getCorrelationId())
        .orElseGet(() -> AccompanyingDocument.builder()
            .notificationReferenceNumber(session.getNotificationReferenceNumber())
            .uploadId(session.getUploadId())
            .correlationId(session.getCorrelationId())
            .scanStatus(ScanStatus.PENDING)
            .files(new ArrayList<>())
            .expireAt(notificationExpireAt(session.getNotificationReferenceNumber()))
            .build());
  }

  private Instant notificationExpireAt(String notificationReferenceNumber) {
    Optional<NotificationExpiry> expiry =
        notificationRepository.findExpiryByReferenceNumber(notificationReferenceNumber);
    if (expiry.isEmpty()) {
      log.warn("Notification {} not found, so its new document has no expireAt",
          notificationReferenceNumber);
      return null;
    }
    return expiry.orElseThrow().getExpireAt();
  }

  private UploadSession saveSession(UploadSession session) {
    try {
      UploadSession saved = uploadSessionRepository.save(session);
      log.info(
          "Registered uploadId {} correlationId {} for notification {}",
          session.getUploadId(),
          session.getCorrelationId(),
          session.getNotificationReferenceNumber());
      return saved;
    } catch (DuplicateKeyException _) {
      log.warn(
          "Duplicate uploadId {} or correlationId {} for notification {}",
          session.getUploadId(),
          session.getCorrelationId(),
          session.getNotificationReferenceNumber());
      throw new ConflictException(
          "An upload session already exists for uploadId " + session.getUploadId()
              + " or correlationId " + session.getCorrelationId());
    }
  }

  private static String extractCorrelationId(CdpScanResultPayload payload) {
    String correlationId = payload.correlationId();
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

  private static ScanStatus resolveScanStatus(CdpScanResultPayload payload) {
    Integer rejectedCount = payload.numberOfRejectedFiles();
    return rejectedCount != null && rejectedCount == 0
        ? ScanStatus.COMPLETE
        : ScanStatus.REJECTED;
  }
}
