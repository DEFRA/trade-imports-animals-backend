package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultForm;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.ServiceUnavailableException;

@WebMvcTest(DocumentController.class)
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "app.base-url=http://localhost:8085"
})
class DocumentControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  @Nested
  class Initiate {

    @Test
    void shouldReturn201WithLocationHeader() throws Exception {
      String ref = "DRAFT.IMP.2026.00000001";
      DocumentUploadRequest request = new DocumentUploadRequest(DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15));
      DocumentUploadResponse serviceResponse = new DocumentUploadResponse("upload-abc-123", "http://localhost:8085/document-uploads/upload-abc-123/file");

      when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class)))
          .thenReturn(serviceResponse);

      mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", "http://localhost:8085/document-uploads/upload-abc-123"))
          .andExpect(jsonPath("$.uploadId").value("upload-abc-123"))
          .andExpect(jsonPath("$.uploadUrl").value("http://localhost:8085/document-uploads/upload-abc-123/file"));
    }

    @Test
    void shouldReturn400_whenDocumentReferenceIsBlank() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"","dateOfIssue":"2026-01-15"}
          """;

      mockMvc.perform(post("/notifications/{ref}/document-uploads", "DRAFT.IMP.2026.00000001")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentReference").exists());
    }

    @Test
    void shouldReturn400_whenDocumentReferenceExceeds100Chars() throws Exception {
      String longRef = "A".repeat(101);
      String body = String.format(
          "{\"documentType\":\"ITAHC\",\"documentReference\":\"%s\",\"dateOfIssue\":\"2026-01-15\"}",
          longRef);

      mockMvc.perform(post("/notifications/{ref}/document-uploads", "DRAFT.IMP.2026.00000001")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentReference").exists());
    }

    @Test
    void shouldReturn400_whenDocumentTypeIsNull() throws Exception {
      String body = """
          {"documentReference":"UKGB2026001","dateOfIssue":"2026-01-15"}
          """;

      mockMvc.perform(post("/notifications/{ref}/document-uploads", "DRAFT.IMP.2026.00000001")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentType").exists());
    }

    @Test
    void shouldReturn400_whenDateOfIssueIsNull() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001"}
          """;

      mockMvc.perform(post("/notifications/{ref}/document-uploads", "DRAFT.IMP.2026.00000001")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.dateOfIssue").exists());
    }

    @Test
    void shouldIgnoreUnknownFieldsLikeRedirectUrl() throws Exception {
      String ref = "DRAFT.IMP.2026.00000001";
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"2026-01-15","redirectUrl":"/anything"}
          """;
      DocumentUploadResponse serviceResponse = new DocumentUploadResponse(
          "upload-abc-123", "https://cdp-uploader.example/upload/abc");

      when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class)))
          .thenReturn(serviceResponse);

      mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated());

      ArgumentCaptor<DocumentUploadRequest> requestCaptor =
          ArgumentCaptor.forClass(DocumentUploadRequest.class);
      verify(documentService).initiate(eq(ref), requestCaptor.capture());
      DocumentUploadRequest captured = requestCaptor.getValue();
      assertThat(captured.documentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(captured.documentReference()).isEqualTo("UKGB2026001");
      assertThat(captured.dateOfIssue()).isEqualTo(LocalDate.of(2026, 1, 15));
    }
  }

  @Nested
  class ListDocuments {

    @Test
    void shouldReturn200WithDocumentList() throws Exception {
      String ref = "DRAFT.IMP.2026.00000001";

      AccompanyingDocument doc = AccompanyingDocument.builder()
          .id("doc-id-1")
          .notificationReferenceNumber(ref)
          .uploadId("upload-abc-123")
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .scanStatus(ScanStatus.COMPLETE)
          .files(List.of())
          .build();

      when(documentService.findByNotificationRef(ref)).thenReturn(List.of(doc));

      mockMvc.perform(get("/notifications/{ref}/document-uploads", ref)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items").isArray())
          .andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].uploadId").value("upload-abc-123"))
          .andExpect(jsonPath("$.items[0].scanStatus").value("COMPLETE"));
    }

    @Test
    void shouldReturn200WithEmptyList() throws Exception {
      String ref = "DRAFT.IMP.2026.00000001";
      when(documentService.findByNotificationRef(ref)).thenReturn(List.of());

      mockMvc.perform(get("/notifications/{ref}/document-uploads", ref)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items").isArray())
          .andExpect(jsonPath("$.items.length()").value(0));
    }
  }

  @Nested
  class GetByUploadId {

    @Test
    void shouldReturn200WithDocument() throws Exception {
      String uploadId = "upload-abc-123";

      AccompanyingDocument doc = AccompanyingDocument.builder()
          .id("doc-id-1")
          .notificationReferenceNumber("DRAFT.IMP.2026.00000001")
          .uploadId(uploadId)
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .scanStatus(ScanStatus.PENDING)
          .files(List.of())
          .build();

      when(documentService.findByUploadId(uploadId)).thenReturn(doc);

      mockMvc.perform(get("/document-uploads/{id}", uploadId)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.uploadId").value(uploadId))
          .andExpect(jsonPath("$.documentType").value("ITAHC"))
          .andExpect(jsonPath("$.scanStatus").value("PENDING"));
    }

    @Test
    void shouldReturn404_whenUploadIdUnknown() throws Exception {
      String unknownId = "unknown-upload-id";
      when(documentService.findByUploadId(unknownId))
          .thenThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId));

      mockMvc.perform(get("/document-uploads/{id}", unknownId)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + unknownId));
    }
  }

  @Nested
  class ScanResult {

    @Test
    void shouldReturn204() throws Exception {
      String uploadId = "upload-abc-123";
      CdpScanResultForm form = new CdpScanResultForm(Map.of());
      CdpScanResultPayload payload = new CdpScanResultPayload("ready", Map.of(), form, 0);

      doNothing().when(documentService).handleScanResult(eq(uploadId), any(CdpScanResultPayload.class));

      mockMvc.perform(post("/document-uploads/{id}/scan-results", uploadId)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload)))
          .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturn404_whenCorrelationIdUnknown() throws Exception {
      String pathSegment = "pending";
      String unknownCorrelationId = "unknown-correlation-id";
      CdpScanResultForm form = new CdpScanResultForm(Map.of());
      CdpScanResultPayload payload = new CdpScanResultPayload(
          "ready", Map.of("correlationId", unknownCorrelationId), form, 0);

      doThrow(new NotFoundException(
          "No accompanying document found with correlationId: " + unknownCorrelationId))
          .when(documentService).handleScanResult(eq(pathSegment), any(CdpScanResultPayload.class));

      mockMvc.perform(post("/document-uploads/{id}/scan-results", pathSegment)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with correlationId: " + unknownCorrelationId));
    }

    @Test
    void shouldReturn400_whenCorrelationIdMissing() throws Exception {
      String pathSegment = "pending";
      CdpScanResultForm form = new CdpScanResultForm(Map.of());
      CdpScanResultPayload payload = new CdpScanResultPayload("ready", Map.of(), form, 0);

      doThrow(new BadRequestException(
          "Scan callback missing required correlationId in metadata"))
          .when(documentService).handleScanResult(eq(pathSegment), any(CdpScanResultPayload.class));

      mockMvc.perform(post("/document-uploads/{id}/scan-results", pathSegment)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(payload)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.detail").value(
              "Scan callback missing required correlationId in metadata"));
    }
  }

  @Nested
  class DownloadFile {

    @Test
    void shouldReturn200WithStreamBody() throws Exception {
      String uploadId = "upload-abc-123";
      byte[] fileContent = "PDF file content".getBytes();

      UploadedFile uploadedFile = new UploadedFile(
          "test-doc.pdf",
          "application/pdf",
          (long) fileContent.length,
          uploadId + "/some-internal-file-id",
          "documents-bucket",
          FileStatus.COMPLETE,
          "sha256abc",
          "application/pdf",
          false,
          null);

      when(documentService.findFile(uploadId)).thenReturn(uploadedFile);
      doNothing().when(s3DocumentService).streamToOutput(any(String.class), any());

      mockMvc.perform(get("/document-uploads/{uploadId}/file", uploadId))
          .andExpect(status().isOk())
          .andExpect(header().string(
              "Content-Disposition",
              "attachment; filename=\"=?UTF-8?Q?test-doc.pdf?=\"; filename*=UTF-8''test-doc.pdf"))
          .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void shouldFallBackToOctetStream_whenStoredContentTypeIsMalformed() throws Exception {
      String uploadId = "upload-abc-123";
      byte[] fileContent = "binary".getBytes();
      UploadedFile uploadedFile = new UploadedFile(
          "weird.bin",
          "not/a valid/media type",
          (long) fileContent.length,
          uploadId + "/file-id",
          "documents-bucket",
          FileStatus.COMPLETE,
          "sha256abc",
          "application/octet-stream",
          false,
          null);
      when(documentService.findFile(uploadId)).thenReturn(uploadedFile);
      doNothing().when(s3DocumentService).streamToOutput(any(String.class), any());

      mockMvc.perform(get("/document-uploads/{uploadId}/file", uploadId))
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Type", "application/octet-stream"));
    }

    @Test
    void shouldReturn404_whenUploadIdUnknown() throws Exception {
      String unknownId = "unknown-upload-id";
      when(documentService.findFile(unknownId))
          .thenThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId));

      mockMvc.perform(get("/document-uploads/{uploadId}/file", unknownId))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + unknownId));
    }
  }

  @Nested
  class UploadFile {

    @Test
    void shouldReturn202() throws Exception {
      String uploadId = "upload-abc-123";
      MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "PDF content".getBytes());
      doNothing().when(documentService).proxyFileToUploader(eq(uploadId), any());

      mockMvc.perform(multipart("/document-uploads/{id}/file", uploadId)
              .file(file))
          .andExpect(status().isAccepted());

      verify(documentService).proxyFileToUploader(eq(uploadId), any());
    }

    @Test
    void shouldReturn404_whenServiceThrowsNotFound() throws Exception {
      String uploadId = "upload-unknown";
      MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "PDF content".getBytes());
      doThrow(new NotFoundException("No accompanying document found with uploadId: " + uploadId))
          .when(documentService).proxyFileToUploader(eq(uploadId), any());

      mockMvc.perform(multipart("/document-uploads/{id}/file", uploadId)
              .file(file))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + uploadId));
    }

    @Test
    void shouldReturn502_whenServiceThrowsServiceUnavailable() throws Exception {
      String uploadId = "upload-abc-123";
      MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "PDF content".getBytes());
      doThrow(new ServiceUnavailableException("cdp-uploader file upload failed at transport level"))
          .when(documentService).proxyFileToUploader(eq(uploadId), any());

      mockMvc.perform(multipart("/document-uploads/{id}/file", uploadId)
              .file(file))
          .andExpect(status().isBadGateway())
          .andExpect(jsonPath("$.title").value("Upstream Service Error"))
          .andExpect(jsonPath("$.detail").value("cdp-uploader file upload failed at transport level"));
    }
  }

  @Nested
  class Delete {

    @Test
    void shouldReturn204() throws Exception {
      String uploadId = "upload-abc-123";
      doNothing().when(documentService).deleteByUploadId(uploadId);

      mockMvc.perform(delete("/document-uploads/{id}", uploadId))
          .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturn404_whenUnknown() throws Exception {
      String unknownId = "unknown-upload-id";
      doThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId))
          .when(documentService).deleteByUploadId(unknownId);

      mockMvc.perform(delete("/document-uploads/{id}", unknownId))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + unknownId));
    }
  }
}
