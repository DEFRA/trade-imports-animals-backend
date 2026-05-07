package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

/**
 * Covers the https default-port branch of the redirect-URL origin check: when both the
 * configured frontend base URL and the supplied redirectUrl omit the port, both must
 * normalise to 443 and compare equal. Kept separate from {@link DocumentControllerTest}
 * because the frontend base URL is a class-level Spring property.
 */
@WebMvcTest(DocumentController.class)
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "cdp.backend.base-url=http://localhost:8085",
    "cdp.frontend.base-url=https://prod.example.com"
})
class DocumentControllerHttpsDefaultPortTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  @Test
  void post_shouldReturn201_whenRedirectUrlAndFrontendBothOmitHttpsPort() throws Exception {
    String ref = "DRAFT.IMP.2026.00000001";
    DocumentUploadRequest request = new DocumentUploadRequest(
        DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15),
        "https://prod.example.com/notifications/" + ref + "/upload-complete");
    DocumentUploadResponse serviceResponse = new DocumentUploadResponse(
        "upload-abc-123", "https://cdp-uploader.example/upload/abc");

    when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class), any(String.class)))
        .thenReturn(serviceResponse);

    mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated());
  }
}
