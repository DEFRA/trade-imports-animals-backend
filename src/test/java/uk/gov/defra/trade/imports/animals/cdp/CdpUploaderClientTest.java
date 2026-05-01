package uk.gov.defra.trade.imports.animals.cdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestBodySpec;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;
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
    when(responseSpec.onStatus(any(), any())).thenAnswer(
        simulateErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"));

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

    when(responseSpec.onStatus(any(), any())).thenAnswer(
        simulateErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "{\"error\":\"invalid request\"}"));

    // When / Then
    assertThatThrownBy(() -> cdpUploaderClient.initiate(request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("cdp-uploader returned an error response: HTTP 422");
  }

  @Test
  void initiate_shouldWrapResourceAccessExceptionAsServiceUnavailable_whenConnectionRefused() {
    // Given — simulate a transport-level failure (e.g. connection refused) by having the
    // RestClient's onStatus chain throw a ResourceAccessException (a RestClientException subtype).
    // This mirrors what Spring's RestClient does when the underlying HTTP client cannot reach
    // the upstream service.
    CdpUploaderInitiateRequest request = new CdpUploaderInitiateRequest(
        "https://frontend/redirect",
        "https://backend/callback",
        "my-bucket",
        "DRAFT.IMP.2026.abc",
        20971520L,
        List.of("application/pdf"),
        null);

    ResourceAccessException transportFailure =
        new ResourceAccessException("I/O error: Connection refused");
    when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    when(responseSpec.body(CdpUploaderInitiateResponse.class)).thenThrow(transportFailure);

    // When / Then — caller sees the same exception type as for HTTP errors, with the
    // original RestClientException preserved on the cause chain.
    assertThatThrownBy(() -> cdpUploaderClient.initiate(request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("cdp-uploader is unreachable")
        .hasCause(transportFailure);
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
    AtomicReference<Predicate<HttpStatusCode>> capturedPredicate = new AtomicReference<>();
    when(responseSpec.onStatus(any(), any())).thenAnswer(invocation -> {
      capturedPredicate.set(invocation.getArgument(0));
      return responseSpec;
    });
    // initiate() return value not asserted in this test — only the captured predicate matters
    when(responseSpec.body(CdpUploaderInitiateResponse.class)).thenReturn(null);

    cdpUploaderClient.initiate(request);

    // Then — predicate should match non-2xx codes but not 2xx
    assertThat(capturedPredicate.get()).isNotNull();
    assertThat(capturedPredicate.get().test(HttpStatus.OK)).isFalse();
    assertThat(capturedPredicate.get().test(HttpStatus.CREATED)).isFalse();
    assertThat(capturedPredicate.get().test(HttpStatus.BAD_REQUEST)).isTrue();
    assertThat(capturedPredicate.get().test(HttpStatus.SERVICE_UNAVAILABLE)).isTrue();
    assertThat(capturedPredicate.get().test(HttpStatus.INTERNAL_SERVER_ERROR)).isTrue();
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  private org.mockito.stubbing.Answer<ResponseSpec> simulateErrorResponse(
      HttpStatus status, String body) {
    return invocation -> {
      Predicate<HttpStatusCode> predicate = invocation.getArgument(0);
      ErrorHandler handler = invocation.getArgument(1);

      if (predicate.test(status)) {
        ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
        try {
          when(mockResponse.getStatusCode()).thenReturn(status);
          when(mockResponse.getBody())
              .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
          handler.handle(mock(ClientHttpRequest.class), mockResponse);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return responseSpec;
    };
  }
}
