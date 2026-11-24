package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.micrometer.core.annotation.Timed;
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

  @Timed("service.registerUpload.time")
  public UploadSession registerUpload(
      String notificationReferenceNumber, RegisterUploadRequest request) {
    UploadSession session = UploadSession.builder()
        .notificationReferenceNumber(notificationReferenceNumber)
        .uploadId(request.uploadId())
        .correlationId(request.correlationId())
        .build();

    return saveSession(session);
  }

  @Timed("service.findSession.time")
  public UploadSession findSession(String notificationReferenceNumber, String correlationId) {
    return uploadSessionRepository
        .findByNotificationReferenceNumberAndCorrelationId(
            notificationReferenceNumber, correlationId)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found for notification " + notificationReferenceNumber
                + " with correlationId: " + correlationId));
  }

  @Transactional
  @Timed("service.claimUpload.time")
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

  @Transactional
  @Timed("service.handleScanResult.time")
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

  @Timed("service.deleteByUploadId.time")
  public void deleteByUploadId(String uploadId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    accompanyingDocumentRepository.delete(document);
    uploadSessionRepository.findByUploadId(uploadId).ifPresent(uploadSessionRepository::delete);
    log.info("Deleted AccompanyingDocument and its session, uploadId {} correlationId {}",
        uploadId, document.getCorrelationId());
  }

  @Timed("service.findByNotificationReferenceNumber.time")
  public List<AccompanyingDocument> findByNotificationReferenceNumber(
      String notificationReferenceNumber) {
    return accompanyingDocumentRepository
        .findAllByNotificationReferenceNumber(notificationReferenceNumber);
  }

  @Timed("service.findClaimedScannedCleanByNotificationReferenceNumber.time")
  public List<AccompanyingDocument> findClaimedScannedCleanByNotificationReferenceNumber(
      String notificationReferenceNumber) {
    return accompanyingDocumentRepository
        .findAllByNotificationReferenceNumberAndScanStatusAndClaimedIsTrueOrderByCreatedAsc(
            notificationReferenceNumber, ScanStatus.COMPLETE);
  }

  @Timed("service.deleteForNotificationReferenceNumbers.time")
  public void deleteForNotificationReferenceNumbers(List<String> notificationReferenceNumbers) {
    Objects.requireNonNull(
        notificationReferenceNumbers, "notificationReferenceNumbers must not be null");
    log.info("Deleting accompanying documents and upload sessions for {} notification(s)",
        notificationReferenceNumbers.size());
    accompanyingDocumentRepository
        .deleteAllByNotificationReferenceNumberIn(notificationReferenceNumbers);
    uploadSessionRepository.deleteAllByNotificationReferenceNumberIn(notificationReferenceNumbers);
  }

  @Timed("service.findByUploadId.time")
  public AccompanyingDocument findByUploadId(String uploadId) {
    return accompanyingDocumentRepository.findByUploadId(uploadId)
        .orElseThrow(
            () -> new NotFoundException("No accompanying document found with uploadId: " + uploadId));
  }

  @Timed("service.notificationReferenceNumberForUploadId.time")
  public String notificationReferenceNumberForUploadId(String uploadId) {
    return uploadSessionRepository.findByUploadId(uploadId)
        .map(UploadSession::getNotificationReferenceNumber)
        .orElseThrow(() -> new NotFoundException(
            "No upload session found with uploadId: " + uploadId));
  }

  @Timed("service.findFile.time")
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
