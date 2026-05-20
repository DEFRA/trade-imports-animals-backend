package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultForm;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderInitiateRequest;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderInitiateResponse;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderClient;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.ServiceUnavailableException;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  @Mock
  private AccompanyingDocumentRepository accompanyingDocumentRepository;

  @Mock
  private CdpUploaderClient cdpUploaderClient;

  @Mock
  private CdpConfig cdpConfig;

  @Mock
  private CdpConfig.S3Config s3Config;

  private DocumentService documentService;

  private static final String BACKEND_BASE_URL = "http://backend";

  @BeforeEach
  void setUp() {
    documentService = new DocumentService(
        accompanyingDocumentRepository,
        cdpUploaderClient,
        new AppConfig(BACKEND_BASE_URL),
        cdpConfig);
  }

  private void stubCdpConfig() {
    when(cdpConfig.uploader()).thenReturn(
        new CdpConfig.UploaderConfig("http://localhost:7337", 52428800L, List.of("application/pdf")));
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn("documents-bucket");
  }

  @Nested
  class Initiate {

    @Test
    void initiate_shouldCallCdpUploaderAndSaveWithPendingStatus() {
      // Given
      final String uploadId = UUID.randomUUID().toString();
      String notificationRef = "GBN-AG-26-ABC123";

      DocumentUploadRequest request = new DocumentUploadRequest(
          DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15));

      stubCdpConfig();

      CdpUploaderInitiateResponse uploaderResponse =
          new CdpUploaderInitiateResponse(uploadId, "/status/" + uploadId);
      when(cdpUploaderClient.initiate(any(CdpUploaderInitiateRequest.class)))
          .thenReturn(uploaderResponse);

      AccompanyingDocument savedDoc = AccompanyingDocument.builder()
          .uploadId(uploadId)
          .scanStatus(ScanStatus.PENDING)
          .build();
      when(accompanyingDocumentRepository.save(any(AccompanyingDocument.class)))
          .thenReturn(savedDoc);

      // When
      DocumentUploadResponse response = documentService.initiate(notificationRef, request);

      // Then — uploadUrl is reconstructed from app.base-url + uploadId, pointing at
      // the backend's own file-proxy endpoint rather than cdp-uploader directly
      assertThat(response).isNotNull();
      assertThat(response.uploadId()).isEqualTo(uploadId);
      assertThat(response.uploadUrl()).isEqualTo("http://backend/document-uploads/" + uploadId + "/file");

      // Then — assert on the request sent to cdp-uploader: metadata.correlationId is present
      ArgumentCaptor<CdpUploaderInitiateRequest> initiateCaptor =
          ArgumentCaptor.forClass(CdpUploaderInitiateRequest.class);
      verify(cdpUploaderClient).initiate(initiateCaptor.capture());
      String metadataCorrelationId = initiateCaptor.getValue().metadata().get("correlationId");
      assertThat(metadataCorrelationId).isNotBlank();
      assertThat(initiateCaptor.getValue().redirect()).isEqualTo("/");

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
      assertThat(UUID.fromString(saved.getCorrelationId())).isNotNull();
    }

    @Test
    void initiate_shouldThrowConflictException_whenSaveThrowsDuplicateKeyException() {
      // Given — production code catches DuplicateKeyException and re-throws ConflictException (→ 409)
      String notificationRef = "GBN-AG-26-CNCR00";

      DocumentUploadRequest request = new DocumentUploadRequest(DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15));

      stubCdpConfig();

      CdpUploaderInitiateResponse uploaderResponse =
          new CdpUploaderInitiateResponse("upload-id-dup", "/status/upload-id-dup");
      when(cdpUploaderClient.initiate(any(CdpUploaderInitiateRequest.class)))
          .thenReturn(uploaderResponse);

      when(accompanyingDocumentRepository.save(any(AccompanyingDocument.class)))
          .thenThrow(new DuplicateKeyException("duplicate key: uploadId"));

      // When / Then
      assertThatThrownBy(() -> documentService.initiate(notificationRef, request))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("upload-id-dup");
    }
  }

  /**
   * Status transitions and file list population are covered end-to-end in {@code DocumentIT}
   * (real MongoDB, real HTTP). Unit tests here only cover error paths and resolution semantics
   * that the IT tests cannot exercise cheaply.
   */
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
  }

  @Nested
  class ProxyFileToUploader {

    @Test
    void proxyFileToUploader_callsCdpUploaderClient_whenDocumentExists() {
      // Given
      String uploadId = "upload-id-proxy-001";
      MultipartFile file = mock(MultipartFile.class);

      AccompanyingDocument document = AccompanyingDocument.builder().uploadId(uploadId).build();
      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.of(document));

      // When
      documentService.proxyFileToUploader(uploadId, file);

      // Then
      verify(cdpUploaderClient).uploadFile(uploadId, file);
    }

    @Test
    void proxyFileToUploader_propagatesServiceUnavailableException_whenUploaderFails() {
      // Given
      String uploadId = "upload-id-proxy-fail";
      MultipartFile file = mock(MultipartFile.class);

      AccompanyingDocument document = AccompanyingDocument.builder().uploadId(uploadId).build();
      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.of(document));

      ServiceUnavailableException uploaderFailure =
          new ServiceUnavailableException("cdp-uploader file upload failed at transport level");
      doThrow(uploaderFailure).when(cdpUploaderClient).uploadFile(uploadId, file);

      // When / Then
      assertThatThrownBy(() -> documentService.proxyFileToUploader(uploadId, file))
          .isInstanceOf(ServiceUnavailableException.class)
          .isSameAs(uploaderFailure);
    }

    @Test
    void proxyFileToUploader_throwsNotFound_whenDocumentMissing() {
      // Given
      String uploadId = "upload-id-proxy-unknown";
      MultipartFile file = mock(MultipartFile.class);

      when(accompanyingDocumentRepository.findByUploadId(uploadId))
          .thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> documentService.proxyFileToUploader(uploadId, file))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining(uploadId);

      verify(cdpUploaderClient, never()).uploadFile(any(), any());
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
