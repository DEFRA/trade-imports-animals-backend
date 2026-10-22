package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  @Mock
  private AccompanyingDocumentRepository accompanyingDocumentRepository;

  @Mock
  private UploadSessionRepository uploadSessionRepository;

  private DocumentService documentService;

  @BeforeEach
  void setUp() {
    documentService =
        new DocumentService(accompanyingDocumentRepository, uploadSessionRepository);
  }

  private void saveReturnsWhatItWasGiven() {
    when(accompanyingDocumentRepository.save(any(AccompanyingDocument.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static UploadSession sessionOf(
      String notificationRef, String uploadId, String correlationId) {
    return UploadSession.builder()
        .notificationReferenceNumber(notificationRef)
        .uploadId(uploadId)
        .correlationId(correlationId)
        .build();
  }

  @Nested
  class RegisterUpload {

    @Test
    void registerUpload_shouldSaveASessionCarryingBothIdentifiers() {
      String notificationRef = "GBN-AG-26-ABC123";
      String uploadId = UUID.randomUUID().toString();
      String correlationId = UUID.randomUUID().toString();
      when(uploadSessionRepository.save(any(UploadSession.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      UploadSession registered = documentService.registerUpload(
          notificationRef, new RegisterUploadRequest(uploadId, correlationId));

      assertThat(registered.getNotificationReferenceNumber()).isEqualTo(notificationRef);
      assertThat(registered.getUploadId()).isEqualTo(uploadId);
      assertThat(registered.getCorrelationId()).isEqualTo(correlationId);
    }

    /**
     * The page opens a session every time it is painted, so registering one must not leave the
     * notification holding a document that nobody uploaded.
     */
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
    private static final String NOTIFICATION_REF = "GBN-AG-26-CLM001";

    private void sessionIsOpen() {
      when(uploadSessionRepository.findByUploadId(UPLOAD_ID))
          .thenReturn(Optional.of(sessionOf(NOTIFICATION_REF, UPLOAD_ID, CORRELATION_ID)));
    }

    private AccompanyingDocument scanned() {
      return AccompanyingDocument.builder()
          .notificationReferenceNumber(NOTIFICATION_REF)
          .uploadId(UPLOAD_ID)
          .correlationId(CORRELATION_ID)
          .scanStatus(ScanStatus.COMPLETE)
          .files(new ArrayList<>())
          .build();
    }

    /**
     * The details normally arrive before the scan callback, so there is nothing to add them to and
     * the claim is what brings the document into being.
     */
    @Test
    void claimUpload_shouldCreateTheDocument_whenNothingHasArrivedForTheSessionYet() {
      sessionIsOpen();
      when(accompanyingDocumentRepository.findByCorrelationId(CORRELATION_ID))
          .thenReturn(Optional.empty());
      saveReturnsWhatItWasGiven();

      AccompanyingDocument claimed = documentService.claimUpload(
          UPLOAD_ID,
          new ClaimUploadRequest(
              DocumentType.ITAHC, "UKGB2026001", LocalDate.parse("2026-01-15")));

      assertThat(claimed.getNotificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
      assertThat(claimed.getUploadId()).isEqualTo(UPLOAD_ID);
      assertThat(claimed.getCorrelationId()).isEqualTo(CORRELATION_ID);
      assertThat(claimed.isClaimed()).isTrue();
      assertThat(claimed.getDocumentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(claimed.getDocumentReference()).isEqualTo("UKGB2026001");
      assertThat(claimed.getDateOfIssue()).isEqualTo(LocalDate.parse("2026-01-15"));
    }

    /**
     * Where the scan got in first, the claim must add to what it recorded rather than start a
     * second document for the same upload.
     */
    @Test
    void claimUpload_shouldKeepTheScanResult_whenTheCallbackArrivedFirst() {
      sessionIsOpen();
      when(accompanyingDocumentRepository.findByCorrelationId(CORRELATION_ID))
          .thenReturn(Optional.of(scanned()));
      saveReturnsWhatItWasGiven();

      AccompanyingDocument claimed = documentService.claimUpload(
          UPLOAD_ID,
          new ClaimUploadRequest(
              DocumentType.ITAHC, "UKGB2026001", LocalDate.parse("2026-01-15")));

      assertThat(claimed.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
      assertThat(claimed.isClaimed()).isTrue();
      verify(accompanyingDocumentRepository, times(1)).save(any(AccompanyingDocument.class));
    }

    @Test
    void claimUpload_shouldReplaceTheDetails_whenTheDocumentIsAlreadyClaimed() {
      AccompanyingDocument alreadyClaimed = scanned();
      alreadyClaimed.setClaimed(true);
      alreadyClaimed.setDocumentReference("UKGBFIRST");
      sessionIsOpen();
      when(accompanyingDocumentRepository.findByCorrelationId(CORRELATION_ID))
          .thenReturn(Optional.of(alreadyClaimed));
      saveReturnsWhatItWasGiven();

      AccompanyingDocument claimed = documentService.claimUpload(
          UPLOAD_ID,
          new ClaimUploadRequest(
              DocumentType.ITAHC, "UKGBSECOND", LocalDate.parse("2026-02-20")));

      assertThat(claimed.isClaimed()).isTrue();
      assertThat(claimed.getDocumentReference()).isEqualTo("UKGBSECOND");
      verify(accompanyingDocumentRepository).save(alreadyClaimed);
    }

    @Test
    void claimUpload_shouldThrowNotFound_whenNoSessionWasOpenedUnderThatId() {
      when(uploadSessionRepository.findByUploadId("missing")).thenReturn(Optional.empty());
      ClaimUploadRequest request = new ClaimUploadRequest(
          DocumentType.ITAHC, "UKGB2026001", LocalDate.parse("2026-01-15"));

      assertThatThrownBy(() -> documentService.claimUpload("missing", request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("missing");
    }
  }

  @Nested
  class HandleScanResult {

    @Test
    void handleScanResult_shouldThrowBadRequest_whenCorrelationIdMissing() {
      // Given — payload metadata has no correlationId entry
      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload("ready", Map.of(), form, 0);

      // When / Then
      assertThatThrownBy(() -> documentService.handleScanResult("pending", payload))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("correlationId");

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void handleScanResult_shouldThrowBadRequest_whenCorrelationIdBlank() {
      // Given — correlationId is present but blank (whitespace only)
      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready", Map.of("correlationId", "   "), form, 0);

      // When / Then
      assertThatThrownBy(() -> documentService.handleScanResult("pending", payload))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("correlationId");

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void handleScanResult_shouldThrowNotFound_whenCorrelationIdUnknown() {
      // Given — correlationId is present but no session was opened under it
      String correlationId = "unknown-correlation-id";
      when(uploadSessionRepository.findByCorrelationId(correlationId))
          .thenReturn(Optional.empty());

      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready", Map.of("correlationId", correlationId), form, 0);

      // When / Then
      assertThatThrownBy(() -> documentService.handleScanResult("pending", payload))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(correlationId);

      verify(accompanyingDocumentRepository, never()).save(any());
    }

    @Test
    void handleScanResult_shouldSetStatusToRejected_whenNumberOfRejectedFilesIsNull() {
      // Given — null numberOfRejectedFiles must fail-closed to REJECTED (not silently COMPLETE)
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

      // When
      documentService.handleScanResult("pending", payload);

      // Then — fail-closed: null count → REJECTED, never COMPLETE
      verify(accompanyingDocumentRepository).save(captor.capture());
      assertThat(captor.getValue().getScanStatus()).isEqualTo(ScanStatus.REJECTED);
    }

    /**
     * Headline regression guard for the multi-PENDING ambiguity that motivated correlationId.
     *
     * <p>Two PENDING documents share the same notification reference (legal — a user can start a
     * second upload before the first leaves PENDING). A scan callback arrives for one. With the
     * old {@code findFirst(notificationRef, PENDING)} resolver this was non-deterministic and could
     * silently update the wrong record. With correlationId routing, only the targeted document
     * transitions; the sibling stays PENDING with empty {@code files}.
     */
    @Test
    void handleScanResult_updatesOnlyMatchingDocument_whenMultiplePendingForSameRef() {
      // Given — two PENDING docs for the same notification ref, each with a distinct correlationId
      String notificationRef = "GBN-AG-26-MXST00";
      String targetCorrelationId = "corr-target";
      String siblingCorrelationId = "corr-sibling";

      AccompanyingDocument target = AccompanyingDocument.builder()
          .uploadId("upload-target")
          .correlationId(targetCorrelationId)
          .notificationReferenceNumber(notificationRef)
          .scanStatus(ScanStatus.PENDING)
          .files(new ArrayList<>())
          .build();
      AccompanyingDocument sibling = AccompanyingDocument.builder()
          .uploadId("upload-sibling")
          .correlationId(siblingCorrelationId)
          .notificationReferenceNumber(notificationRef)
          .scanStatus(ScanStatus.PENDING)
          .files(new ArrayList<>())
          .build();

      when(uploadSessionRepository.findByCorrelationId(targetCorrelationId)).thenReturn(
          Optional.of(sessionOf(notificationRef, "upload-target", targetCorrelationId)));
      when(accompanyingDocumentRepository.findByCorrelationId(targetCorrelationId))
          .thenReturn(Optional.of(target));

      CdpScanResultForm form = new CdpScanResultForm();
      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready",
          Map.of("notificationReferenceNumber", notificationRef,
                 "correlationId", targetCorrelationId),
          form,
          0);

      // When — fire callback for the target only
      documentService.handleScanResult("pending", payload);

      // Then — only the target is saved, transitioning to COMPLETE
      ArgumentCaptor<AccompanyingDocument> captor = ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository).save(captor.capture());
      AccompanyingDocument saved = captor.getValue();
      assertThat(saved.getCorrelationId()).isEqualTo(targetCorrelationId);
      assertThat(saved.getUploadId()).isEqualTo("upload-target");
      assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);

      // And — the sibling was never looked up nor saved; its in-memory state is unchanged
      verify(accompanyingDocumentRepository, never()).findByCorrelationId(siblingCorrelationId);
      assertThat(sibling.getScanStatus()).isEqualTo(ScanStatus.PENDING);
      assertThat(sibling.getFiles()).isEmpty();
    }

    /**
     * A scanned file is a file that exists, so the callback brings the document into being where
     * the trader's details have not arrived yet. It stays unclaimed, which keeps it off the
     * notification's events until they do.
     */
    @Test
    void handleScanResult_shouldCreateAnUnclaimedDocument_whenItArrivesBeforeTheDetails() {
      String correlationId = "corr-scan-first";
      when(uploadSessionRepository.findByCorrelationId(correlationId)).thenReturn(
          Optional.of(sessionOf("GBN-AG-26-SCN001", "upload-scan-first", correlationId)));
      when(accompanyingDocumentRepository.findByCorrelationId(correlationId))
          .thenReturn(Optional.empty());
      saveReturnsWhatItWasGiven();

      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready", Map.of("correlationId", correlationId), new CdpScanResultForm(), 0);

      documentService.handleScanResult("upload-scan-first", payload);

      ArgumentCaptor<AccompanyingDocument> captor =
          ArgumentCaptor.forClass(AccompanyingDocument.class);
      verify(accompanyingDocumentRepository).save(captor.capture());
      AccompanyingDocument saved = captor.getValue();
      assertThat(saved.getNotificationReferenceNumber()).isEqualTo("GBN-AG-26-SCN001");
      assertThat(saved.getUploadId()).isEqualTo("upload-scan-first");
      assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
      assertThat(saved.isClaimed()).isFalse();
    }
  }

  @Nested
  class DeleteForNotificationRefs {

    @Test
    void deleteForNotificationRefs_shouldDeleteAllDocumentsForGivenRefs() {
      // Given
      List<String> referenceNumbers = List.of("GBN-AG-26-000111", "GBN-AG-26-000222");

      // When
      documentService.deleteForNotificationRefs(referenceNumbers);

      // Then
      verify(accompanyingDocumentRepository).deleteAllByNotificationReferenceNumberIn(referenceNumbers);
      verify(uploadSessionRepository).deleteAllByNotificationReferenceNumberIn(referenceNumbers);
    }

    @Test
    void deleteForNotificationRefs_shouldThrowNullPointerException_whenReferenceNumbersIsNull() {
      // When / Then
      assertThatThrownBy(() -> documentService.deleteForNotificationRefs(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("referenceNumbers must not be null");
    }
  }

  @Nested
  class FindFile {

    @Test
    void findFile_shouldThrowNotFoundException_whenNoFilesPresent() {
      // Given
      String uploadId = "upload-id-004";

      AccompanyingDocument document = AccompanyingDocument.builder()
          .uploadId(uploadId)
          .files(Collections.emptyList())
          .build();
      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.of(document));

      // When / Then
      assertThatThrownBy(() -> documentService.findFile(uploadId))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(uploadId);
    }

    @Test
    void findFile_shouldThrowNotFoundException_whenFileIsRejected() {
      // Given — a file whose s3Key is null indicates it was rejected by the virus scanner
      String uploadId = "upload-id-rejected";

      UploadedFile rejectedFile = new UploadedFile(
          "document.pdf",
          "application/pdf",
          1024L,
          null, // s3Key is null for rejected files
          "documents-bucket",
          FileStatus.REJECTED,
          null,
          null,
          true,
          "File rejected by virus scanner");

      AccompanyingDocument document = AccompanyingDocument.builder()
          .uploadId(uploadId)
          .files(List.of(rejectedFile))
          .build();
      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.of(document));

      // When / Then
      assertThatThrownBy(() -> documentService.findFile(uploadId))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(uploadId);
    }
  }
}
