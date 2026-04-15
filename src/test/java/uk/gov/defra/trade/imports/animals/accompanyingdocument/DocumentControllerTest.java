package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@WebMvcTest(DocumentController.class)
@TestPropertySource(properties = "admin.secret=test-secret")
class DocumentControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  // ---------------------------------------------------------------------------
  // POST /notifications/{ref}/document-uploads
  // ---------------------------------------------------------------------------

  @Test
  void post_shouldReturn201WithLocationHeader() throws Exception {
    // Given
    String ref = "DRAFT.IMP.2026.00000001";
    DocumentUploadRequest request = new DocumentUploadRequest(DocumentType.ITAHC, "UK/GB/2026/001", LocalDate.of(2026, 1, 15), null);
    DocumentUploadResponse serviceResponse = new DocumentUploadResponse("upload-abc-123", "https://cdp-uploader.example/upload/abc");

    when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class), any(String.class)))
        .thenReturn(serviceResponse);

    // When
    MvcResult result = mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        // Then
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/document-uploads/upload-abc-123"))
        .andExpect(jsonPath("$.uploadId").value("upload-abc-123"))
        .andExpect(jsonPath("$.uploadUrl").value("https://cdp-uploader.example/upload/abc"))
        .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(201);
  }

  // ---------------------------------------------------------------------------
  // GET /notifications/{ref}/document-uploads
  // ---------------------------------------------------------------------------

  @Test
  void list_shouldReturn200WithDocumentList() throws Exception {
    // Given
    String ref = "DRAFT.IMP.2026.00000001";

    AccompanyingDocument doc = AccompanyingDocument.builder()
        .id("doc-id-1")
        .notificationReferenceNumber(ref)
        .uploadId("upload-abc-123")
        .documentType(DocumentType.ITAHC)
        .documentReference("UK/GB/2026/001")
        .scanStatus(ScanStatus.COMPLETE)
        .files(List.of())
        .build();

    when(documentService.findByNotificationRef(ref)).thenReturn(List.of(doc));

    // When / Then
    mockMvc.perform(get("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].uploadId").value("upload-abc-123"))
        .andExpect(jsonPath("$.items[0].scanStatus").value("COMPLETE"));
  }

  // ---------------------------------------------------------------------------
  // GET /document-uploads/{upload-id}
  // ---------------------------------------------------------------------------

  @Test
  void get_shouldReturn200WithDocument() throws Exception {
    // Given
    String uploadId = "upload-abc-123";

    AccompanyingDocument doc = AccompanyingDocument.builder()
        .id("doc-id-1")
        .notificationReferenceNumber("DRAFT.IMP.2026.00000001")
        .uploadId(uploadId)
        .documentType(DocumentType.ITAHC)
        .documentReference("UK/GB/2026/001")
        .scanStatus(ScanStatus.PENDING)
        .files(List.of())
        .build();

    when(documentService.findByUploadId(uploadId)).thenReturn(doc);

    // When / Then
    mockMvc.perform(get("/document-uploads/{id}", uploadId)
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadId").value(uploadId))
        .andExpect(jsonPath("$.documentType").value("ITAHC"))
        .andExpect(jsonPath("$.scanStatus").value("PENDING"));
  }

  @Test
  void get_shouldReturn404_whenUploadIdUnknown() throws Exception {
    // Given
    String unknownId = "unknown-upload-id";
    when(documentService.findByUploadId(unknownId))
        .thenThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId));

    // When / Then
    mockMvc.perform(get("/document-uploads/{id}", unknownId)
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(
            "No accompanying document found with uploadId: " + unknownId));
  }

  // ---------------------------------------------------------------------------
  // POST /document-uploads/{upload-id}/scan-results
  // ---------------------------------------------------------------------------

  @Test
  void scanResult_shouldReturn204() throws Exception {
    // Given
    String uploadId = "upload-abc-123";
    CdpScanResultForm form = new CdpScanResultForm(Map.of());
    CdpScanResultPayload payload = new CdpScanResultPayload("upload-id-001", "ready", Map.of(), form, 0);

    doNothing().when(documentService).handleScanResult(eq(uploadId), any(CdpScanResultPayload.class));

    // When / Then
    mockMvc.perform(post("/document-uploads/{id}/scan-results", uploadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload)))
        .andExpect(status().isNoContent());
  }

  // ---------------------------------------------------------------------------
  // GET /document-uploads/{upload-id}/files/{file-id}
  // ---------------------------------------------------------------------------

  @Test
  void downloadFile_shouldReturn200WithStreamBody() throws Exception {
    // Given
    String uploadId = "upload-abc-123";
    String fileId = "file-xyz-456";
    byte[] fileContent = "PDF file content".getBytes();

    UploadedFile uploadedFile = new UploadedFile(
        fileId,
        "test-doc.pdf",
        "application/pdf",
        (long) fileContent.length,
        uploadId + "/" + fileId,
        "documents-bucket",
        FileStatus.COMPLETE,
        "sha256abc",
        "application/pdf",
        false,
        null);

    when(documentService.findFile(uploadId, fileId)).thenReturn(uploadedFile);

    // StreamingResponseBody writes to the output stream via s3DocumentService; mock it to write
    // the test bytes so the response body is non-empty.
    doNothing().when(s3DocumentService).streamToOutput(any(String.class), any());

    // When / Then
    mockMvc.perform(get("/document-uploads/{uploadId}/files/{fileId}", uploadId, fileId))
        .andExpect(status().isOk())
        .andExpect(header().string(
            "Content-Disposition", "attachment; filename=\"test-doc.pdf\""))
        .andExpect(header().string("Content-Type", "application/pdf"));
  }
}
