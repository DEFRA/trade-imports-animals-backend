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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

@WebMvcTest(DocumentController.class)
@EnableConfigurationProperties(AppConfig.class)
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "app.base-url=http://localhost:8085/"
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
  void shouldNormaliseTrailingSlashInLocationHeader() throws Exception {
    String ref = "DRAFT.IMP.2026.00000001";
    DocumentUploadRequest request = new DocumentUploadRequest(DocumentType.ITAHC, "UKGB2026001", LocalDate.of(2026, 1, 15));
    DocumentUploadResponse serviceResponse = new DocumentUploadResponse("upload-abc-123", "http://localhost:8085/document-uploads/upload-abc-123/file");

    when(documentService.initiate(eq(ref), any(DocumentUploadRequest.class)))
        .thenReturn(serviceResponse);

    mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost:8085/document-uploads/upload-abc-123"));
  }
}
