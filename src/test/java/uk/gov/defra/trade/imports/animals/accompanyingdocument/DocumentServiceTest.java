package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.cdp.CdpUploaderClient;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  @Mock
  private AccompanyingDocumentRepository accompanyingDocumentRepository;

  @Mock
  private CdpUploaderClient cdpUploaderClient;

  @Mock
  private CdpConfig cdpConfig;

  @Mock
  private CdpConfig.UploaderConfig uploaderConfig;

  @Mock
  private CdpConfig.BackendConfig backendConfig;

  @Mock
  private CdpConfig.S3Config s3Config;

  private DocumentService documentService;

  @BeforeEach
  void setUp() {
    documentService = new DocumentService(
        accompanyingDocumentRepository,
        cdpUploaderClient,
        cdpConfig);
  }

  // ─── initiate ────────────────────────────────────────────────────────────────

  private void stubCdpConfig() {
    when(cdpConfig.uploader()).thenReturn(uploaderConfig);
    when(cdpConfig.backend()).thenReturn(backendConfig);
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(uploaderConfig.maxFileSize()).thenReturn(52428800L);
    when(uploaderConfig.mimeTypes()).thenReturn(List.of("application/pdf"));
    when(backendConfig.baseUrl()).thenReturn("http://backend");
    when(s3Config.documentsBucket()).thenReturn("documents-bucket");
  }

  @Test
  void initiate_shouldCallCdpUploaderAndSaveWithPendingStatus() {
    // Given
    String notificationRef = "DRAFT.IMP.2026.abc123";
    String redirectUrl = "https://frontend.example.com/documents";

    DocumentUploadRequest request = new DocumentUploadRequest(
        DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15), redirectUrl);

    stubCdpConfig();

    CdpUploaderInitiateResponse uploaderResponse =
        new CdpUploaderInitiateResponse("upload-id-001", "https://cdp-uploader/form/upload-id-001", "https://cdp-uploader/status/upload-id-001");
    when(cdpUploaderClient.initiate(any(CdpUploaderInitiateRequest.class)))
        .thenReturn(uploaderResponse);

    AccompanyingDocument savedDoc = AccompanyingDocument.builder()
        .uploadId("upload-id-001")
        .scanStatus(ScanStatus.PENDING)
        .build();
    when(accompanyingDocumentRepository.save(any(AccompanyingDocument.class)))
        .thenReturn(savedDoc);

    // When
    DocumentUploadResponse response = documentService.initiate(notificationRef, request, redirectUrl);

    // Then — assert on the response returned to the caller
    assertThat(response).isNotNull();
    assertThat(response.uploadId()).isEqualTo("upload-id-001");
    assertThat(response.uploadUrl()).isEqualTo("https://cdp-uploader/form/upload-id-001");

    // Then — assert on the request sent to cdp-uploader: metadata.correlationId is present
    ArgumentCaptor<CdpUploaderInitiateRequest> initiateCaptor =
        ArgumentCaptor.forClass(CdpUploaderInitiateRequest.class);
    verify(cdpUploaderClient).initiate(initiateCaptor.capture());
    String metadataCorrelationId = initiateCaptor.getValue().metadata().get("correlationId");
    assertThat(metadataCorrelationId).isNotBlank();

    // Then — assert on the entity persisted to the repository
    ArgumentCaptor<AccompanyingDocument> captor = ArgumentCaptor.forClass(AccompanyingDocument.class);
    verify(accompanyingDocumentRepository).save(captor.capture());
    AccompanyingDocument saved = captor.getValue();
    assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.PENDING);
    assertThat(saved.getNotificationReferenceNumber()).isEqualTo(notificationRef);
    Instant expectedDateOfIssue = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant();
    assertThat(saved.getDateOfIssue()).isEqualTo(expectedDateOfIssue);

    // Then — the same correlationId is on the saved doc and is a valid UUID
    assertThat(saved.getCorrelationId()).isEqualTo(metadataCorrelationId);
    assertThat(java.util.UUID.fromString(saved.getCorrelationId())).isNotNull();
  }

  // ─── handleScanResult ────────────────────────────────────────────────────────
  // Status transitions and file list population are covered end-to-end in DocumentIT
  // (real MongoDB, real HTTP). Unit tests here only cover error paths and resolution
  // semantics that the IT tests cannot exercise cheaply.

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
    // Given — correlationId is present but no document matches
    String correlationId = "unknown-correlation-id";
    when(accompanyingDocumentRepository.findByCorrelationId(correlationId))
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
    String notificationRef = "DRAFT.IMP.2026.MULTI";
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

  // ─── initiate — DuplicateKeyException → ConflictException (409) ────────────

  @Test
  void initiate_shouldThrowConflictException_whenSaveThrowsDuplicateKeyException() {
    // Given — production code catches DuplicateKeyException and re-throws ConflictException (→ 409)
    String notificationRef = "DRAFT.IMP.2026.concurrent";
    String redirectUrl = null;

    DocumentUploadRequest request = new DocumentUploadRequest(DocumentType.ITAHC, "UKGB2026001", null, null);

    stubCdpConfig();

    CdpUploaderInitiateResponse uploaderResponse =
        new CdpUploaderInitiateResponse("upload-id-dup",
            "https://cdp-uploader/form/upload-id-dup",
            "https://cdp-uploader/status/upload-id-dup");
    when(cdpUploaderClient.initiate(any(CdpUploaderInitiateRequest.class)))
        .thenReturn(uploaderResponse);

    when(accompanyingDocumentRepository.save(any(AccompanyingDocument.class)))
        .thenThrow(new DuplicateKeyException("duplicate key: uploadId"));

    // When / Then
    assertThatThrownBy(() -> documentService.initiate(notificationRef, request, redirectUrl))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("upload-id-dup");
  }

  // ─── deleteForNotificationRefs ───────────────────────────────────────────────

  @Test
  void deleteForNotificationRefs_shouldDeleteAllDocumentsForGivenRefs() {
    // Given
    List<String> referenceNumbers = List.of("DRAFT.IMP.2026.111", "DRAFT.IMP.2026.222");

    // When
    documentService.deleteForNotificationRefs(referenceNumbers);

    // Then
    verify(accompanyingDocumentRepository).deleteAllByNotificationReferenceNumberIn(referenceNumbers);
  }

  @Test
  void deleteForNotificationRefs_shouldThrowNullPointerException_whenReferenceNumbersIsNull() {
    // When / Then
    assertThatThrownBy(() -> documentService.deleteForNotificationRefs(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("referenceNumbers must not be null");
  }

  // ─── findFile ────────────────────────────────────────────────────────────────

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
