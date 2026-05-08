package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

/**
 * Covers profile-dependent behaviour of {@link DocumentController}. Kept separate from
 * {@link DocumentControllerTest} because {@code @ActiveProfiles} forces a different Spring
 * {@code ApplicationContext} that can't be mixed with the default test class without nested-
 * configuration override and full setup duplication.
 *
 * <p>Currently exercises the {@code local} profile, which bypasses the redirectUrl origin check
 * so frontend and backend can run in different network contexts (Docker / native) without
 * having to keep {@code TRADE_IMPORTS_ANIMALS_FRONTEND_BASE_URL} aligned across both.
 */
@WebMvcTest(DocumentController.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "cdp.backend.base-url=http://localhost:8085",
    "cdp.frontend.base-url=http://localhost:3000"
})
class DocumentControllerProfileTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  /**
   * Each row pins a branch the origin guard would reject in a deployed profile, proving the
   * local-profile bypass is total — not a localhost-only or hostname-only relaxation:
   * <ul>
   *   <li>different in-network host — the case that motivated the bypass (Docker frontend
   *       reaches native backend with mismatched TRADE_IMPORTS_ANIMALS_FRONTEND_BASE_URL)</li>
   *   <li>external host — confirms the bypass is unconditional under local; the open-redirect
   *       guard intentionally does not run</li>
   *   <li>malformed URI — confirms even unparseable values pass through</li>
   * </ul>
   */
  @ParameterizedTest(name = "[{index}] {1}")
  @CsvSource(value = {
      "http://trade-imports-animals-frontend:3000/accompanying-documents | different in-network host",
      "https://evil.example.com/steal                                    | external host",
      "http://[invalid                                                   | malformed URI"
  }, delimiter = '|')
  void post_shouldAcceptRedirectUrl_whenLocalProfileActive(String redirectUrl, String scenario) throws Exception {
    String ref = "DRAFT.IMP.2026.00000001";
    String trimmedRedirect = redirectUrl.trim();
    DocumentUploadResponse serviceResponse = new DocumentUploadResponse(
        "upload-abc-123", "https://cdp-uploader.example/upload/abc");

    when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class), eq(trimmedRedirect)))
        .thenReturn(serviceResponse);

    mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"2026-01-15","redirectUrl":"%s"}
                """.formatted(trimmedRedirect)))
        .andExpect(status().isCreated());
  }
}
