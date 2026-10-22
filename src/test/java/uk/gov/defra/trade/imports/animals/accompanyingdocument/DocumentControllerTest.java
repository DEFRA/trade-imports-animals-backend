package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultForm;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@WebMvcTest(DocumentController.class)
@EnableConfigurationProperties(AppConfig.class)
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "app.base-url=http://localhost:8085",
    "outbox.sns.topic-arn=arn:aws:sns:eu-west-2:000000000000:unit-test-outbox.fifo"
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
  class Register {

    private static final String REF = "GBN-AG-26-000001";

    @Test
    void shouldReturn201WithLocationHeaderAndTheRegisteredSession() throws Exception {
      RegisterUploadRequest request =
          new RegisterUploadRequest("upload-abc-123", "correlation-abc-123");

      when(documentService.registerUpload(eq(REF), any(RegisterUploadRequest.class)))
          .thenReturn(UploadSession.builder()
              .notificationReferenceNumber(REF)
              .uploadId("upload-abc-123")
              .correlationId("correlation-abc-123")
              .build());

      mockMvc.perform(post("/notifications/{ref}/document-uploads", REF)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isCreated())
          .andExpect(header().string(
              "Location", "http://localhost:8085/document-uploads/upload-abc-123"))
          .andExpect(jsonPath("$.uploadId").value("upload-abc-123"))
          .andExpect(jsonPath("$.correlationId").value("correlation-abc-123"))
          .andExpect(jsonPath("$.notificationReferenceNumber").value(REF));
    }

    @Test
    void shouldReturn400_whenUploadIdIsBlank() throws Exception {
      String body = """
          {"uploadId":"","correlationId":"correlation-abc-123"}
          """;

      mockMvc.perform(post("/notifications/{ref}/document-uploads", REF)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.uploadId").exists());

      verify(documentService, never()).registerUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenCorrelationIdIsMissing() throws Exception {
      String body = """
          {"uploadId":"upload-abc-123"}
          """;

      mockMvc.perform(post("/notifications/{ref}/document-uploads", REF)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.correlationId").exists());

      verify(documentService, never()).registerUpload(any(), any());
    }
  }

  @Nested
  class Claim {

    private static final String UPLOAD_ID = "upload-abc-123";

    private AccompanyingDocument claimedWith(DocumentType documentType) {
      return AccompanyingDocument.builder()
          .uploadId(UPLOAD_ID)
          .documentType(documentType)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
          .scanStatus(ScanStatus.PENDING)
          .claimed(true)
          .build();
    }

    @Test
    void shouldReturn200WithTheClaimedDocument() throws Exception {
      ClaimUploadRequest request = new ClaimUploadRequest(
          DocumentType.ITAHC, "UKGB2026001", LocalDate.parse("2026-01-15"));

      when(documentService.claimUpload(eq(UPLOAD_ID), any(ClaimUploadRequest.class)))
          .thenReturn(claimedWith(DocumentType.ITAHC));

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID))
          .andExpect(jsonPath("$.claimed").value(true))
          .andExpect(jsonPath("$.documentReference").value("UKGB2026001"));
    }

    @ParameterizedTest
    @EnumSource(
        value = DocumentType.class,
        names = {"ITAHC", "VETERINARY_HEALTH_CERTIFICATE"},
        mode = EnumSource.Mode.EXCLUDE)
    void shouldAcceptEveryPreviouslyRejectedDocumentType(DocumentType documentType)
        throws Exception {
      ClaimUploadRequest expectedRequest = new ClaimUploadRequest(
          documentType, "UKGB2026001", LocalDate.parse("2026-01-15"));
      String body = """
          {
            "documentType":"%s",
            "documentReference":"UKGB2026001",
            "dateOfIssue":"2026-01-15"
          }
          """.formatted(documentType.name());

      when(documentService.claimUpload(UPLOAD_ID, expectedRequest))
          .thenReturn(claimedWith(documentType));

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());

      verify(documentService).claimUpload(UPLOAD_ID, expectedRequest);
    }

    @Test
    void shouldReturn400_whenDocumentReferenceIsBlank() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"","dateOfIssue":"2026-01-15"}
          """;

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
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

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
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

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentType").exists());
    }

    /**
     * {@code dateOfIssue} is a calendar date, so a value carrying a time or an offset must not
     * bind. Jackson's {@code LocalDateDeserializer} is lenient by default and would keep the date
     * part of {@code 2026-01-15T00:00:00Z}; {@code StrictLocalDateModule} turns that off. What
     * this pins is the contract the caller sees — 400 rather than a silently trimmed value, a
     * problem+json body carrying the malformed-request type, and a detail naming the form the
     * caller must send. Nothing is saved.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "2026-01-15T00:00:00Z", "2026-01-15T00:00:00", "2026-01-15T00:00:00+01:00"})
    void post_shouldReturn400_whenDateOfIssueCarriesATimeOrAnOffset(String dateOfIssue)
        throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"%s"}
          """.formatted(dateOfIssue);

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.type")
              .value("https://api.cdp.defra.cloud/problems/malformed-request"))
          .andExpect(jsonPath("$.title").value("Malformed Request"))
          .andExpect(jsonPath("$.detail")
              .value(Matchers.containsString("each date-only field is a date, for example 2026-12-12")));

      verify(documentService, never()).claimUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenDateOfIssueIsNull() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001"}
          """;

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.dateOfIssue").exists());
    }

    @Test
    void shouldIgnoreUnknownFieldsLikeRedirectUrl() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"2026-01-15","redirectUrl":"/anything"}
          """;

      when(documentService.claimUpload(eq(UPLOAD_ID), any(ClaimUploadRequest.class)))
          .thenReturn(claimedWith(DocumentType.ITAHC));

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());

      ArgumentCaptor<ClaimUploadRequest> requestCaptor =
          ArgumentCaptor.forClass(ClaimUploadRequest.class);
      verify(documentService).claimUpload(eq(UPLOAD_ID), requestCaptor.capture());
      ClaimUploadRequest captured = requestCaptor.getValue();
      assertThat(captured.documentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(captured.documentReference()).isEqualTo("UKGB2026001");
      assertThat(captured.dateOfIssue()).isEqualTo(LocalDate.parse("2026-01-15"));
    }
  }
  @Nested
  class ListDocuments {

    @Test
    void shouldReturn200WithDocumentList() throws Exception {
      String ref = "GBN-AG-26-000001";

      AccompanyingDocument doc = AccompanyingDocument.builder()
          .id("doc-id-1")
          .notificationReferenceNumber(ref)
          .uploadId("upload-abc-123")
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
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
          .andExpect(jsonPath("$.items[0].dateOfIssue").value("2026-01-15"))
          .andExpect(jsonPath("$.items[0].scanStatus").value("COMPLETE"));
    }

    @Test
    void shouldReturn200WithEmptyList() throws Exception {
      String ref = "GBN-AG-26-000001";
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
          .notificationReferenceNumber("GBN-AG-26-000001")
          .uploadId(uploadId)
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
          .scanStatus(ScanStatus.PENDING)
          .files(List.of())
          .build();

      when(documentService.findByUploadId(uploadId)).thenReturn(doc);

      mockMvc.perform(get("/document-uploads/{id}", uploadId)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.uploadId").value(uploadId))
          .andExpect(jsonPath("$.documentType").value("ITAHC"))
          .andExpect(jsonPath("$.dateOfIssue").value("2026-01-15"))
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
