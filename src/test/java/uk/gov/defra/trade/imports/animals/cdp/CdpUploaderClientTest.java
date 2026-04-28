package uk.gov.defra.trade.imports.animals.cdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodySpec;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.CdpUploaderInitiateRequest;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.CdpUploaderInitiateResponse;
import uk.gov.defra.trade.imports.animals.exceptions.ServiceUnavailableException;

@ExtendWith(MockitoExtension.class)
class CdpUploaderClientTest {

  @Mock
  private RestClient restClient;

  @Mock
  private RequestBodyUriSpec requestBodyUriSpec;

  @Mock
  private RequestBodySpec requestBodySpec;

  @Mock
  private ResponseSpec responseSpec;

  private CdpUploaderClient cdpUploaderClient;

  @BeforeEach
  void setUp() {
    cdpUploaderClient = new CdpUploaderClient(restClient);

    when(restClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri("/initiate")).thenReturn(requestBodySpec);
    when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
    when(requestBodySpec.body(any(CdpUploaderInitiateRequest.class))).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
  }

  // ─── Happy path ───────────────────────────────────────────────────────────────

  @Test
  void initiate_shouldReturnResponseFromCdpUploader_whenRequestSucceeds() {
    // Given
    CdpUploaderInitiateRequest request = new CdpUploaderInitiateRequest(
        "https://frontend/redirect",
        "https://backend/callback",
        "my-bucket",
        "DRAFT.IMP.2026.abc",
        20971520L,
        List.of("application/pdf"),
        null);

    CdpUploaderInitiateResponse expected =
        new CdpUploaderInitiateResponse(
            "upload-id-001",
            "https://cdp-uploader/form/upload-id-001",
            "https://cdp-uploader/status/upload-id-001");

    when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    when(responseSpec.body(CdpUploaderInitiateResponse.class)).thenReturn(expected);

    // When
    CdpUploaderInitiateResponse actual = cdpUploaderClient.initiate(request);

    // Then
    assertThat(actual).isEqualTo(expected);
    verify(responseSpec).onStatus(any(), any());
  }

  // ─── Error path ───────────────────────────────────────────────────────────────

  @Test
  void initiate_shouldThrowServiceUnavailableException_whenCdpUploaderReturns503() {
    // Given — simulate the onStatus handler executing for a 503 response
    CdpUploaderInitiateRequest request = new CdpUploaderInitiateRequest(
        "https://frontend/redirect",
        "https://backend/callback",
        "my-bucket",
        "DRAFT.IMP.2026.abc",
        20971520L,
        List.of("application/pdf"),
        null);

    // onStatus registers a handler and returns the responseSpec for chaining;
    // we capture and invoke the handler ourselves to simulate a 503 response.
    when(responseSpec.onStatus(any(), any())).thenAnswer(invocation -> {
      java.util.function.Predicate<org.springframework.http.HttpStatusCode> predicate =
          invocation.getArgument(0);
      org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler handler =
          invocation.getArgument(1);

      org.springframework.http.HttpStatusCode status503 = HttpStatus.SERVICE_UNAVAILABLE;
      if (predicate.test(status503)) {
        org.springframework.http.client.ClientHttpResponse mockResponse =
            mock(org.springframework.http.client.ClientHttpResponse.class);
        try {
          when(mockResponse.getStatusCode()).thenReturn(status503);
          when(mockResponse.getBody())
              .thenReturn(new ByteArrayInputStream(
                  "Service unavailable".getBytes(StandardCharsets.UTF_8)));
          handler.handle(null, mockResponse);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      return responseSpec;
    });

    // When / Then
    assertThatThrownBy(() -> cdpUploaderClient.initiate(request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("cdp-uploader returned an error response: HTTP 503");
  }

  @Test
  void initiate_shouldThrowServiceUnavailableException_whenCdpUploaderReturns4xx() {
    // Given — simulate a 422 Unprocessable Entity from cdp-uploader
    CdpUploaderInitiateRequest request = new CdpUploaderInitiateRequest(
        "https://frontend/redirect",
        "https://backend/callback",
        "my-bucket",
        "DRAFT.IMP.2026.abc",
        20971520L,
        List.of("application/pdf"),
        null);

    when(responseSpec.onStatus(any(), any())).thenAnswer(invocation -> {
      java.util.function.Predicate<org.springframework.http.HttpStatusCode> predicate =
          invocation.getArgument(0);
      org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler handler =
          invocation.getArgument(1);

      org.springframework.http.HttpStatusCode status422 = HttpStatus.UNPROCESSABLE_ENTITY;
      if (predicate.test(status422)) {
        org.springframework.http.client.ClientHttpResponse mockResponse =
            mock(org.springframework.http.client.ClientHttpResponse.class);
        try {
          when(mockResponse.getStatusCode()).thenReturn(status422);
          when(mockResponse.getBody())
              .thenReturn(new ByteArrayInputStream(
                  "{\"error\":\"invalid request\"}".getBytes(StandardCharsets.UTF_8)));
          handler.handle(null, mockResponse);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      return responseSpec;
    });

    // When / Then
    assertThatThrownBy(() -> cdpUploaderClient.initiate(request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("cdp-uploader returned an error response: HTTP 422");
  }

  @Test
  void initiate_onStatusPredicate_shouldMatchNon2xxOnly() {
    // Given — verify the predicate passed to onStatus correctly classifies status codes
    CdpUploaderInitiateRequest request = new CdpUploaderInitiateRequest(
        "https://frontend/redirect",
        "https://backend/callback",
        "my-bucket",
        "DRAFT.IMP.2026.abc",
        20971520L,
        List.of("application/pdf"),
        null);

    // Capture the predicate without triggering the handler
    java.util.function.Predicate<org.springframework.http.HttpStatusCode>[] capturedPredicate =
        new java.util.function.Predicate[1];
    when(responseSpec.onStatus(any(), any())).thenAnswer(invocation -> {
      capturedPredicate[0] = invocation.getArgument(0);
      return responseSpec;
    });
    when(responseSpec.body(CdpUploaderInitiateResponse.class)).thenReturn(null);

    cdpUploaderClient.initiate(request);

    // Then — predicate should match non-2xx codes but not 2xx
    assertThat(capturedPredicate[0]).isNotNull();
    assertThat(capturedPredicate[0].test(HttpStatus.OK)).isFalse();
    assertThat(capturedPredicate[0].test(HttpStatus.CREATED)).isFalse();
    assertThat(capturedPredicate[0].test(HttpStatus.BAD_REQUEST)).isTrue();
    assertThat(capturedPredicate[0].test(HttpStatus.SERVICE_UNAVAILABLE)).isTrue();
    assertThat(capturedPredicate[0].test(HttpStatus.INTERNAL_SERVER_ERROR)).isTrue();
  }
}
