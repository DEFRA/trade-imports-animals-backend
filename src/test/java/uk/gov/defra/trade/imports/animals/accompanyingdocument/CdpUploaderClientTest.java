package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodySpec;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;

@ExtendWith(MockitoExtension.class)
class CdpUploaderClientTest {

  @Mock
  private RestClient cdpUploaderRestClient;

  @Mock
  private RequestBodyUriSpec requestBodyUriSpec;

  @Mock
  private RequestBodySpec requestBodySpec;

  @Mock
  private ResponseSpec responseSpec;

  private CdpUploaderClient cdpUploaderClient;

  @BeforeEach
  void setUp() {
    cdpUploaderClient = new CdpUploaderClient(cdpUploaderRestClient);
  }

  @Test
  void initiate_shouldPostToInitiateUriWithJsonBodyAndReturnResponse() {
    // Given
    CdpUploaderInitiateRequest request = new CdpUploaderInitiateRequest(
        "https://frontend/redirect",
        "https://backend/document-uploads/{uploadId}/scan-results",
        "documents-bucket",
        "DRAFT.IMP.2026.abc",
        20971520L,
        List.of("application/pdf"),
        Map.of("notificationReferenceNumber", "DRAFT.IMP.2026.abc"));

    CdpUploaderInitiateResponse expectedResponse =
        new CdpUploaderInitiateResponse(
            "upload-id-999",
            "https://cdp-uploader/form/upload-id-999",
            "https://cdp-uploader/status/upload-id-999");

    when(cdpUploaderRestClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri("/initiate")).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(APPLICATION_JSON)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(request)).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(CdpUploaderInitiateResponse.class)).thenReturn(expectedResponse);

    // When
    CdpUploaderInitiateResponse response = cdpUploaderClient.initiate(request);

    // Then
    assertThat(response).isNotNull();
    assertThat(response.uploadId()).isEqualTo("upload-id-999");
    assertThat(response.uploadUrl()).isEqualTo("https://cdp-uploader/form/upload-id-999");

    verify(cdpUploaderRestClient).post();
    verify(requestBodyUriSpec).uri("/initiate");
    verify(requestBodySpec).contentType(APPLICATION_JSON);
    verify(requestBodySpec).body(request);
    verify(requestBodySpec).retrieve();
    verify(responseSpec).body(CdpUploaderInitiateResponse.class);
  }
}
