package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * Regression test for the trailing-slash normalisation on {@code cdp.backend.base-url}.
 *
 * <p>The main {@link DocumentControllerTest} configures {@code cdp.backend.base-url} without a
 * trailing slash, so it cannot detect a regression that removes the slash-stripping logic. This
 * class overrides the property with a trailing-slash value and asserts the {@code Location} header
 * still contains a single slash between base URL and path — i.e. no {@code "//"} after the
 * authority.
 */
@WebMvcTest(DocumentController.class)
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "cdp.backend.base-url=http://localhost:8085/",
    "cdp.frontend.base-url=http://localhost:3000"
})
class DocumentControllerTrailingSlashTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  @Test
  void post_shouldReturnLocationWithSingleSlash_whenBackendBaseUrlHasTrailingSlash() throws Exception {
    // Given — backend base URL is configured with a trailing slash (see @TestPropertySource above)
    String ref = "DRAFT.IMP.2026.00000001";
    DocumentUploadRequest request = new DocumentUploadRequest(
        DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15), null);
    DocumentUploadResponse serviceResponse = new DocumentUploadResponse(
        "upload-abc-123", "https://cdp-uploader.example/upload/abc");

    when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class), any(String.class)))
        .thenReturn(serviceResponse);

    // When / Then — the controller must strip the trailing slash before concatenating the path
    // segment, so the Location header has a single slash, not "http://localhost:8085//document-uploads/...".
    mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost:8085/document-uploads/upload-abc-123"));
  }
}
