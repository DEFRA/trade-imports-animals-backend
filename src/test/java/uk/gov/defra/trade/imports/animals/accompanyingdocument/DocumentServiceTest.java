package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.logEventsDuring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultForm;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  private static final Instant EXPIRE_AT = Instant.parse("2026-11-11T09:30:00Z");

  @Mock
  private AccompanyingDocumentRepository accompanyingDocumentRepository;

  @Mock
  private UploadSessionRepository uploadSessionRepository;

  @Mock
  private NotificationRepository notificationRepository;

  private DocumentService documentService;

  @BeforeEach
  void setUp() {
    documentService = new DocumentService(
        accompanyingDocumentRepository, uploadSessionRepository, notificationRepository);
  }

  private void notificationExpiresAt(String notificationReferenceNumber, Instant expireAt) {
    lenient().when(notificationRepository.findExpiryByReferenceNumber(notificationReferenceNumber))
        .thenReturn(Optional.of(() -> expireAt));
  }

  private void saveReturnsWhatItWasGiven() {
    when(accompanyingDocumentRepository.save(any(AccompanyingDocument.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private void noDocumentYetThenSaveReturnsIt(String correlationId) {
    when(accompanyingDocumentRepository.findByCorrelationId(correlationId))
        .thenReturn(Optional.empty());
    saveReturnsWhatItWasGiven();
  }

  private static ClaimUploadRequest claimRequest() {
    return new ClaimUploadRequest(
        DocumentType.ITAHC, "UKGB2026001", LocalDate.parse("2026-01-15"));
  }

  private static CdpScanResultPayload readyPayload(String correlationId) {
    return new CdpScanResultPayload(
        "ready", Map.of("correlationId", correlationId), new CdpScanResultForm(), 0);
  }

  private static UploadSession sessionOf(
      String notificationReferenceNumber, String uploadId, String correlationId) {
    return UploadSession.builder()
        .notificationReferenceNumber(notificationReferenceNumber)
        .uploadId(uploadId)
        .correlationId(correlationId)
        .build();
  }

  private static List<String> warningsLoggedDuring(Runnable action) {
    return logEventsDuring(DocumentService.class, action).stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Nested
  class RegisterUpload {

    @Test
    void registerUpload_shouldSaveASessionCarryingBothIdentifiers() {
      String notificationReferenceNumber = "GBN-AG-26-ABC123";
      String uploadId = UUID.randomUUID().toString();
      String correlationId = UUID.randomUUID().toString();
      when(uploadSessionRepository.save(any(UploadSession.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      UploadSession registered = documentService.registerUpload(
          notificationReferenceNumber, new RegisterUploadRequest(uploadId, correlationId));

      assertThat(registered.getNotificationReferenceNumber()).isEqualTo(notificationReferenceNumber);
      assertThat(registered.getUploadId()).isEqualTo(uploadId);
      assertThat(registered.getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    void registerUpload_shouldNotWriteADocument() {
      when(uploadSessionRepository.save(any(UploadSession.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      documentService.registerUpload(
          "GBN-AG-26-NODOC0",
          new RegisterUploadRequest("upload-id-nodoc", "correlation-id-nodoc"));

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void registerUpload_shouldThrowConflict_whenTheIdentifiersAreAlreadyRegistered() {
      when(uploadSessionRepository.save(any(UploadSession.class)))
          .thenThrow(new DuplicateKeyException("duplicate key: uploadId"));
      RegisterUploadRequest request =
          new RegisterUploadRequest("upload-id-dup", "correlation-id-dup");

      assertThatThrownBy(() -> documentService.registerUpload("GBN-AG-26-CNCR00", request))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("upload-id-dup");
    }
  }

  @Nested
  class FindSession {

    @Test
    void findSession_shouldReturnTheSessionTheNotificationOpened() {
      UploadSession session = sessionOf("GBN-AG-26-SES001", "upload-id-ses", "correlation-id-ses");
      when(uploadSessionRepository.findByNotificationReferenceNumberAndCorrelationId(
          "GBN-AG-26-SES001", "correlation-id-ses")).thenReturn(Optional.of(session));

      assertThat(documentService.findSession("GBN-AG-26-SES001", "correlation-id-ses"))
          .isSameAs(session);
    }

    @Test
    void findSession_shouldThrowNotFound_whenThatNotificationHasNoSessionUnderTheHandle() {
      when(uploadSessionRepository.findByNotificationReferenceNumberAndCorrelationId(
          "GBN-AG-26-OTHER0", "correlation-id-ses")).thenReturn(Optional.empty());

      assertThatThrownBy(
          () -> documentService.findSession("GBN-AG-26-OTHER0", "correlation-id-ses"))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("correlation-id-ses");
    }
  }

  @Nested
  class ClaimUpload {

    private static final String UPLOAD_ID = "upload-id-claim";
    private static final String CORRELATION_ID = "correlation-id-claim";
    private static final String NOTIFICATION_REFERENCE_NUMBER = "GBN-AG-26-CLM001";

    private void sessionIsOpen() {
      when(uploadSessionRepository.findByUploadId(UPLOAD_ID))
          .thenReturn(Optional.of(sessionOf(NOTIFICATION_REFERENCE_NUMBER, UPLOAD_ID, CORRELATION_ID)));
    }

    private AccompanyingDocument scanned() {
      return AccompanyingDocument.builder()
          .notificationReferenceNumber(NOTIFICATION_REFERENCE_NUMBER)
          .uploadId(UPLOAD_ID)
          .correlationId(CORRELATION_ID)
          .scanStatus(ScanStatus.COMPLETE)
          .files(new ArrayList<>())
          .build();
    }

    @Test
    void claimUpload_shouldCreateTheDocument_whenNothingHasArrivedForTheSessionYet() {
      sessionIsOpen();
      noDocumentYetThenSaveReturnsIt(CORRELATION_ID);

      AccompanyingDocument claimed = documentService.claimUpload(UPLOAD_ID, claimRequest());

      assertThat(claimed.getNotificationReferenceNumber()).isEqualTo(NOTIFICATION_REFERENCE_NUMBER);
      assertThat(claimed.getUploadId()).isEqualTo(UPLOAD_ID);
      assertThat(claimed.getCorrelationId()).isEqualTo(CORRELATION_ID);
      assertThat(claimed.isClaimed()).isTrue();
      assertThat(claimed.getDocumentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(claimed.getDocumentReference()).isEqualTo("UKGB2026001");
      assertThat(claimed.getDateOfIssue()).isEqualTo(LocalDate.parse("2026-01-15"));
    }

    @Test
    void claimUpload_shouldGiveANewDocumentItsNotificationsExpireAt() {
      sessionIsOpen();
      notificationExpiresAt(NOTIFICATION_REFERENCE_NUMBER, EXPIRE_AT);
      noDocumentYetThenSaveReturnsIt(CORRELATION_ID);

      AccompanyingDocument claimed = documentService.claimUpload(UPLOAD_ID, claimRequest());

      assertThat(claimed.getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    @Test
    void claimUpload_shouldLeaveANewDocumentWithoutExpireAt_whenItsNotificationHasNone() {
      sessionIsOpen();
      notificationExpiresAt(NOTIFICATION_REFERENCE_NUMBER, null);
      noDocumentYetThenSaveReturnsIt(CORRELATION_ID);

      AccompanyingDocument claimed = documentService.claimUpload(UPLOAD_ID, claimRequest());

      assertThat(claimed.getExpireAt()).isNull();
    }

    @Test
    void claimUpload_shouldNotWarn_whenTheNotificationIsFoundWithNoExpireAt() {
      sessionIsOpen();
      notificationExpiresAt(NOTIFICATION_REFERENCE_NUMBER, null);
      noDocumentYetThenSaveReturnsIt(CORRELATION_ID);
      ClaimUploadRequest request = claimRequest();

      List<String> warnings =
          warningsLoggedDuring(() -> documentService.claimUpload(UPLOAD_ID, request));

      assertThat(warnings).isEmpty();
    }

    @Test
    void claimUpload_shouldWarnWithTheReferenceNumberAndSetNoExpireAt_whenTheNotificationIsNotFound() {
      sessionIsOpen();
      when(notificationRepository.findExpiryByReferenceNumber(NOTIFICATION_REFERENCE_NUMBER))
          .thenReturn(Optional.empty());
      noDocumentYetThenSaveReturnsIt(CORRELATION_ID);
      ClaimUploadRequest request = claimRequest();
      List<AccompanyingDocument> claimed = new ArrayList<>();

      List<String> warnings = warningsLoggedDuring(
          () -> claimed.add(documentService.claimUpload(UPLOAD_ID, request)));

      assertThat(warnings).singleElement().asString().contains(NOTIFICATION_REFERENCE_NUMBER);
      assertThat(claimed).singleElement()
          .satisfies(document -> assertThat(document.getExpireAt()).isNull());
    }

    @Test
    void claimUpload_shouldKeepTheScanResultAndWriteNoSecondDocument_whenTheCallbackArrivedFirst() {
      sessionIsOpen();
      when(accompanyingDocumentRepository.findByCorrelationId(CORRELATION_ID))
          .thenReturn(Optional.of(scanned()));
      saveReturnsWhatItWasGiven();

      AccompanyingDocument claimed = documentService.claimUpload(UPLOAD_ID, claimRequest());

      assertThat(claimed.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
      assertThat(claimed.isClaimed()).isTrue();
      verify(accompanyingDocumentRepository, times(1)).save(any(AccompanyingDocument.class));
    }

    @Test
    void claimUpload_shouldKeepTheStoredExpireAt_whenTheDocumentAlreadyExists() {
      AccompanyingDocument stored = scanned();
      stored.setExpireAt(EXPIRE_AT);
      sessionIsOpen();
      notificationExpiresAt(NOTIFICATION_REFERENCE_NUMBER, EXPIRE_AT.plus(1, ChronoUnit.DAYS));
      when(accompanyingDocumentRepository.findByCorrelationId(CORRELATION_ID))
          .thenReturn(Optional.of(stored));
      saveReturnsWhatItWasGiven();

      AccompanyingDocument claimed = documentService.claimUpload(UPLOAD_ID, claimRequest());

      assertThat(claimed.getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    @Test
    void claimUpload_shouldReplaceTheDetails_whenTheDocumentIsAlreadyClaimed() {
      AccompanyingDocument alreadyClaimed = scanned();
      alreadyClaimed.setClaimed(true);
      alreadyClaimed.setDocumentType(DocumentType.JOURNEY_LOG);
      alreadyClaimed.setDocumentReference("UKGBFIRST");
      alreadyClaimed.setDateOfIssue(LocalDate.parse("2026-01-15"));
      sessionIsOpen();
      when(accompanyingDocumentRepository.findByCorrelationId(CORRELATION_ID))
          .thenReturn(Optional.of(alreadyClaimed));
      saveReturnsWhatItWasGiven();

      AccompanyingDocument claimed = documentService.claimUpload(
          UPLOAD_ID,
          new ClaimUploadRequest(
              DocumentType.ITAHC, "UKGBSECOND", LocalDate.parse("2026-02-20")));

      assertThat(claimed.isClaimed()).isTrue();
      assertThat(claimed.getDocumentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(claimed.getDocumentReference()).isEqualTo("UKGBSECOND");
      assertThat(claimed.getDateOfIssue()).isEqualTo(LocalDate.parse("2026-02-20"));
      verify(accompanyingDocumentRepository).save(alreadyClaimed);
    }

    @Test
    void claimUpload_shouldThrowNotFound_whenNoSessionWasOpenedUnderThatId() {
      when(uploadSessionRepository.findByUploadId("missing")).thenReturn(Optional.empty());
      ClaimUploadRequest request = claimRequest();

      assertThatThrownBy(() -> documentService.claimUpload("missing", request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("missing");
    }
  }

  @Nested
  class HandleScanResult {

    @Test
    void handleScanResult_shouldThrowBadRequest_whenCorrelationIdMissing() {
      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload("ready", Map.of(), form, 0);

      assertThatThrownBy(() -> documentService.handleScanResult(payload))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("correlationId");

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void handleScanResult_shouldThrowBadRequest_whenCorrelationIdBlank() {
      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready", Map.of("correlationId", "   "), form, 0);

      assertThatThrownBy(() -> documentService.handleScanResult(payload))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("correlationId");

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void handleScanResult_shouldThrowNotFound_whenCorrelationIdUnknown() {
      String correlationId = "unknown-correlation-id";
      when(uploadSessionRepository.findByCorrelationId(correlationId))
          .thenReturn(Optional.empty());

      CdpScanResultPayload payload = readyPayload(correlationId);

      assertThatThrownBy(() -> documentService.handleScanResult(payload))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(correlationId);

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void handleScanResult_shouldSetStatusToRejected_whenNumberOfRejectedFilesIsNull() {
      String correlationId = "corr-null-rejected";

      AccompanyingDocument document = AccompanyingDocument.builder()
          .uploadId("upload-id-null-rejected")
          .correlationId(correlationId)
          .scanStatus(ScanStatus.PENDING)
          .files(new ArrayList<>())
          .build();
      when(uploadSessionRepository.findByCorrelationId(correlationId)).thenReturn(
          Optional.of(sessionOf("GBN-AG-26-NLR000", "upload-id-null-rejected", correlationId)));
      when(accompanyingDocumentRepository.findByCorrelationId(correlationId))
          .thenReturn(Optional.of(document));

      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready", Map.of("correlationId", correlationId), form, null);

      ArgumentCaptor<AccompanyingDocument> captor = ArgumentCaptor.forClass(AccompanyingDocument.class);

      documentService.handleScanResult(payload);

      verify(accompanyingDocumentRepository).save(captor.capture());
      assertThat(captor.getValue().getScanStatus()).isEqualTo(ScanStatus.REJECTED);
    }

    @Test
    void handleScanResult_shouldSaveTheDocumentMatchingTheCorrelationId() {
      String notificationReferenceNumber = "GBN-AG-26-MXST00";
      String targetCorrelationId = "corr-target";

      AccompanyingDocument target = AccompanyingDocument.builder()
          .uploadId("upload-target")
          .correlationId(targetCorrelationId)
          .notificationReferenceNumber(notificationReferenceNumber)
          .scanStatus(ScanStatus.PENDING)
          .files(new ArrayList<>())
          .build();

      when(uploadSessionRepository.findByCorrelationId(targetCorrelationId)).thenReturn(
          Optional.of(sessionOf(notificationReferenceNumber, "upload-target", targetCorrelationId)));
      when(accompanyingDocumentRepository.findByCorrelationId(targetCorrelationId))
          .thenReturn(Optional.of(target));

      documentService.handleScanResult(readyPayload(targetCorrelationId));

      ArgumentCaptor<AccompanyingDocument> captor = ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository).save(captor.capture());
      AccompanyingDocument saved = captor.getValue();
      assertThat(saved.getCorrelationId()).isEqualTo(targetCorrelationId);
      assertThat(saved.getUploadId()).isEqualTo("upload-target");
      assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
    }

    @Test
    void handleScanResult_shouldKeepTheClaimOnTheStoredDocument_whenTheDetailsArrivedFirst() {
      String correlationId = "corr-claim-first";
      AccompanyingDocument claimedDocument = AccompanyingDocument.builder()
          .id("doc-claim-first")
          .notificationReferenceNumber("GBN-AG-26-CLF001")
          .uploadId("upload-claim-first")
          .correlationId(correlationId)
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
          .scanStatus(ScanStatus.PENDING)
          .claimed(true)
          .files(new ArrayList<>())
          .build();
      when(uploadSessionRepository.findByCorrelationId(correlationId)).thenReturn(
          Optional.of(sessionOf("GBN-AG-26-CLF001", "upload-claim-first", correlationId)));
      when(accompanyingDocumentRepository.findByCorrelationId(correlationId))
          .thenReturn(Optional.of(claimedDocument));

      documentService.handleScanResult(readyPayload(correlationId));

      ArgumentCaptor<AccompanyingDocument> captor =
          ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository, times(1)).save(captor.capture());
      AccompanyingDocument saved = captor.getValue();
      assertThat(saved.getId()).isEqualTo("doc-claim-first");
      assertThat(saved.isClaimed()).isTrue();
      assertThat(saved.getDocumentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(saved.getDocumentReference()).isEqualTo("UKGB2026001");
      assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
    }

    @Test
    void handleScanResult_shouldCreateAnUnclaimedDocument_whenItArrivesBeforeTheDetails() {
      String correlationId = "corr-scan-first";
      when(uploadSessionRepository.findByCorrelationId(correlationId)).thenReturn(
          Optional.of(sessionOf("GBN-AG-26-SCN001", "upload-scan-first", correlationId)));
      noDocumentYetThenSaveReturnsIt(correlationId);

      documentService.handleScanResult(readyPayload(correlationId));

      ArgumentCaptor<AccompanyingDocument> captor =
          ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository).save(captor.capture());
      AccompanyingDocument saved = captor.getValue();
      assertThat(saved.getNotificationReferenceNumber()).isEqualTo("GBN-AG-26-SCN001");
      assertThat(saved.getUploadId()).isEqualTo("upload-scan-first");
      assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
      assertThat(saved.isClaimed()).isFalse();
    }

    @Test
    void handleScanResult_shouldGiveANewDocumentItsNotificationsExpireAt() {
      String correlationId = "corr-scan-expiry";
      when(uploadSessionRepository.findByCorrelationId(correlationId)).thenReturn(
          Optional.of(sessionOf("GBN-AG-26-SCN002", "upload-scan-expiry", correlationId)));
      notificationExpiresAt("GBN-AG-26-SCN002", EXPIRE_AT);
      noDocumentYetThenSaveReturnsIt(correlationId);

      documentService.handleScanResult(readyPayload(correlationId));

      ArgumentCaptor<AccompanyingDocument> captor =
          ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository).save(captor.capture());
      assertThat(captor.getValue().getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    @Test
    void handleScanResult_shouldKeepTheStoredExpireAt_whenTheDocumentAlreadyExists() {
      String correlationId = "corr-scan-stored-expiry";
      AccompanyingDocument stored = AccompanyingDocument.builder()
          .notificationReferenceNumber("GBN-AG-26-SCN003")
          .uploadId("upload-scan-stored-expiry")
          .correlationId(correlationId)
          .scanStatus(ScanStatus.PENDING)
          .claimed(true)
          .files(new ArrayList<>())
          .expireAt(EXPIRE_AT)
          .build();
      when(uploadSessionRepository.findByCorrelationId(correlationId)).thenReturn(
          Optional.of(sessionOf("GBN-AG-26-SCN003", "upload-scan-stored-expiry", correlationId)));
      notificationExpiresAt("GBN-AG-26-SCN003", EXPIRE_AT.plus(1, ChronoUnit.DAYS));
      when(accompanyingDocumentRepository.findByCorrelationId(correlationId))
          .thenReturn(Optional.of(stored));

      documentService.handleScanResult(readyPayload(correlationId));

      ArgumentCaptor<AccompanyingDocument> captor =
          ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository).save(captor.capture());
      assertThat(captor.getValue().getExpireAt()).isEqualTo(EXPIRE_AT);
    }
  }

  @Nested
  class DeleteForNotificationReferenceNumbers {

    @Test
    void deleteForNotificationReferenceNumbers_shouldDeleteTheDocumentsAndSessions() {
      List<String> notificationReferenceNumbers = List.of("GBN-AG-26-000111", "GBN-AG-26-000222");

      documentService.deleteForNotificationReferenceNumbers(notificationReferenceNumbers);

      verify(accompanyingDocumentRepository)
          .deleteAllByNotificationReferenceNumberIn(notificationReferenceNumbers);
      verify(uploadSessionRepository)
          .deleteAllByNotificationReferenceNumberIn(notificationReferenceNumbers);
    }

    @Test
    void deleteForNotificationReferenceNumbers_shouldThrowNullPointerException_whenGivenNull() {
      assertThatThrownBy(() -> documentService.deleteForNotificationReferenceNumbers(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("notificationReferenceNumbers must not be null");
    }
  }

  @Nested
  class FindFile {

    @Test
    void findFile_shouldThrowNotFoundException_whenNoFilesPresent() {
      String uploadId = "upload-id-004";

      AccompanyingDocument document = AccompanyingDocument.builder()
          .uploadId(uploadId)
          .files(Collections.emptyList())
          .build();
      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.of(document));

      assertThatThrownBy(() -> documentService.findFile(uploadId))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(uploadId);
    }

    @Test
    void findFile_shouldThrowNotFoundException_whenFileIsRejected() {
      String uploadId = "upload-id-rejected";

      UploadedFile rejectedFile = new UploadedFile(
          "document.pdf",
          "application/pdf",
          1024L,
          null,
          "documents-bucket",
          FileStatus.REJECTED,
          null,
          null,
          true,
          "FILE_VIRUS",
          "The selected file contains a virus");

      AccompanyingDocument document = AccompanyingDocument.builder()
          .uploadId(uploadId)
          .files(List.of(rejectedFile))
          .build();
      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.of(document));

      assertThatThrownBy(() -> documentService.findFile(uploadId))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(uploadId);
    }
  }
}
