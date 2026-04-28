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
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
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

  @Test
  void initiate_shouldCallCdpUploaderAndSaveWithPendingStatus() {
    // Given
    String notificationRef = "DRAFT.IMP.2026.abc123";
    String redirectUrl = "https://frontend.example.com/documents";

    DocumentUploadRequest request = new DocumentUploadRequest(
        DocumentType.ITAHC, "UK/GB/2026/001", LocalDate.of(2026, 1, 15), redirectUrl);

    when(cdpConfig.uploader()).thenReturn(uploaderConfig);
    when(cdpConfig.backend()).thenReturn(backendConfig);
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(uploaderConfig.maxFileSize()).thenReturn(52428800L);
    when(uploaderConfig.mimeTypes()).thenReturn(List.of("application/pdf"));
    when(backendConfig.baseUrl()).thenReturn("http://backend");
    when(s3Config.documentsBucket()).thenReturn("documents-bucket");

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

    // Then — assert on the entity persisted to the repository
    ArgumentCaptor<AccompanyingDocument> captor = ArgumentCaptor.forClass(AccompanyingDocument.class);
    verify(accompanyingDocumentRepository).save(captor.capture());
    AccompanyingDocument saved = captor.getValue();
    assertThat(saved.getScanStatus()).isEqualTo(ScanStatus.PENDING);
    assertThat(saved.getNotificationReferenceNumber()).isEqualTo(notificationRef);
    Instant expectedDateOfIssue = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant();
    assertThat(saved.getDateOfIssue()).isEqualTo(expectedDateOfIssue);
  }

  // ─── handleScanResult ────────────────────────────────────────────────────────
  // Status transitions and file list population are covered end-to-end in DocumentIT
  // (real MongoDB, real HTTP). Unit tests here only cover error paths that the IT tests
  // cannot exercise cheaply.

  @Test
  void handleScanResult_shouldThrowNotFoundException_whenUploadIdUnknown() {
    // Given
    String uploadId = "non-existent-upload-id";
    when(accompanyingDocumentRepository.findByUploadId(uploadId))
        .thenReturn(Optional.empty());

    CdpScanResultForm form = new CdpScanResultForm();
    CdpScanResultPayload payload = new CdpScanResultPayload("ready", Map.of(), form, 0);

    // When / Then
    assertThatThrownBy(() -> documentService.handleScanResult(uploadId, payload))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining(uploadId);

    verify(accompanyingDocumentRepository, never()).save(any());
  }

  // ─── initiate — DuplicateKeyException → ConflictException (409) ────────────

  @Test
  void initiate_shouldThrowConflictException_whenSaveThrowsDuplicateKeyException() {
    // Given — production code catches DuplicateKeyException and re-throws ConflictException (→ 409)
    String notificationRef = "DRAFT.IMP.2026.concurrent";
    String redirectUrl = "";

    DocumentUploadRequest request = new DocumentUploadRequest(DocumentType.ITAHC, "UK/GB/2026/001", null, null);

    when(cdpConfig.uploader()).thenReturn(uploaderConfig);
    when(cdpConfig.backend()).thenReturn(backendConfig);
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(uploaderConfig.maxFileSize()).thenReturn(52428800L);
    when(uploaderConfig.mimeTypes()).thenReturn(List.of("application/pdf"));
    when(backendConfig.baseUrl()).thenReturn("http://backend");
    when(s3Config.documentsBucket()).thenReturn("documents-bucket");

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
