package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

/**
 * Verifies the {@code local} Spring profile bypasses the redirectUrl origin check, so a frontend
 * and backend running in different network contexts (Docker / native) don't have to keep
 * {@code FRONTEND_BASE_URL} aligned across both.
 */
@WebMvcTest(DocumentController.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "cdp.backend.base-url=http://localhost:8085",
    "cdp.frontend.base-url=http://localhost:3000"
})
class DocumentControllerLocalProfileTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  @Test
  void post_shouldAcceptMismatchedRedirectUrl_whenLocalProfileActive() throws Exception {
    String ref = "DRAFT.IMP.2026.00000001";
    String mismatchedRedirect = "http://trade-imports-animals-frontend:3000/accompanying-documents";
    DocumentUploadResponse serviceResponse = new DocumentUploadResponse(
        "upload-abc-123", "https://cdp-uploader.example/upload/abc");

    when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class), eq(mismatchedRedirect)))
        .thenReturn(serviceResponse);

    DocumentUploadRequest request = new DocumentUploadRequest(
        DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15), mismatchedRedirect);

    mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"2026-01-15","redirectUrl":"%s"}
                """.formatted(mismatchedRedirect)))
        .andExpect(status().isCreated());

    verify(documentService).initiate(eq(ref), any(DocumentUploadRequest.class), eq(mismatchedRedirect));
  }
}
