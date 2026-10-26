package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionFixtures.registerUploadRequest;
import static uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionFixtures.registeredSession;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    "app.base-url=http://localhost:8085/",
    "outbox.sns.topic-arn=arn:aws:sns:eu-west-2:000000000000:unit-test-outbox.fifo"
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
    String ref = "GBN-AG-26-000001";

    when(documentService.registerUpload(eq(ref), any(RegisterUploadRequest.class)))
        .thenReturn(registeredSession(ref));

    mockMvc.perform(post("/notifications/{ref}/document-uploads", ref)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(registerUploadRequest())))
        .andExpect(status().isCreated())
        .andExpect(header().string(
            "Location",
            "http://localhost:8085/document-uploads/" + UploadSessionFixtures.UPLOAD_ID));
  }
}
