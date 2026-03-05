package uk.gov.defra.trade.imports.animals.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for TraceIdPropagationInterceptor.
 *
 * <p>Tests verify that the x-cdp-request-id header is correctly propagated from SLF4J MDC to
 * outbound HTTP requests, with proper null/blank handling.
 */
@ExtendWith(MockitoExtension.class)
class TraceIdPropagationInterceptorTest {

  private static final String TRACE_ID_HEADER = "x-cdp-request-id";
  private static final String MDC_TRACE_ID = "trace.id";

  @Mock private HttpRequest request;

  @Mock private ClientHttpRequestExecution execution;

  @Mock private ClientHttpResponse response;

  @Captor private ArgumentCaptor<HttpRequest> requestCaptor;

  @Captor private ArgumentCaptor<byte[]> bodyCaptor;

  private TraceIdPropagationInterceptor interceptor;
  private HttpHeaders headers;

  @BeforeEach
  void setUp() throws IOException {
    interceptor = new TraceIdPropagationInterceptor("x-cdp-request-id");
    headers = new HttpHeaders();
    lenient().when(request.getHeaders()).thenReturn(headers);
    lenient().when(execution.execute(any(HttpRequest.class), any(byte[].class))).thenReturn(response);
  }

  @AfterEach
  void tearDown() {
    // Clean MDC after each test to prevent test pollution
    MDC.clear();
  }

  @Test
  void intercept_shouldAddTraceIdHeader_whenMdcContainsTraceId() throws IOException {
    // Given: MDC contains a trace ID
    String expectedTraceId = "test-trace-id-12345";
    MDC.put(MDC_TRACE_ID, expectedTraceId);
    byte[] body = new byte[0];

    // When: Interceptor processes the request
    ClientHttpResponse actualResponse = interceptor.intercept(request, body, execution);

    // Then: x-cdp-request-id header is set with the trace ID
    assertThat(headers.get(TRACE_ID_HEADER)).isNotNull().containsExactly(expectedTraceId);

    // And: Execution is called with the modified request
    verify(execution).execute(request, body);

    // And: Response is returned
    assertThat(actualResponse).isSameAs(response);
  }

  @Test
  void intercept_shouldNotAddHeader_whenMdcTraceIdIsNull() throws IOException {
    // Given: MDC does not contain a trace ID
    MDC.remove(MDC_TRACE_ID);
    byte[] body = new byte[0];

    // When: Interceptor processes the request
    ClientHttpResponse actualResponse = interceptor.intercept(request, body, execution);

    // Then: x-cdp-request-id header is NOT set
    assertThat(headers.get(TRACE_ID_HEADER)).isNull();

    // And: Execution is still called
    verify(execution).execute(request, body);

    // And: Response is returned
    assertThat(actualResponse).isSameAs(response);
  }

  @Test
  void intercept_shouldNotAddHeader_whenMdcTraceIdIsBlank() throws IOException {
    // Given: MDC contains a blank trace ID
    MDC.put(MDC_TRACE_ID, "   ");
    byte[] body = new byte[0];

    // When: Interceptor processes the request
    ClientHttpResponse actualResponse = interceptor.intercept(request, body, execution);

    // Then: x-cdp-request-id header is NOT set
    assertThat(headers.get(TRACE_ID_HEADER)).isNull();

    // And: Execution is still called
    verify(execution).execute(request, body);

    // And: Response is returned
    assertThat(actualResponse).isSameAs(response);
  }

  @Test
  void intercept_shouldNotAddHeader_whenMdcTraceIdIsEmpty() throws IOException {
    // Given: MDC contains an empty trace ID
    MDC.put(MDC_TRACE_ID, "");
    byte[] body = new byte[0];

    // When: Interceptor processes the request
    ClientHttpResponse actualResponse = interceptor.intercept(request, body, execution);

    // Then: x-cdp-request-id header is NOT set
    assertThat(headers.get(TRACE_ID_HEADER)).isNull();

    // And: Execution is still called
    verify(execution).execute(request, body);

    // And: Response is returned
    assertThat(actualResponse).isSameAs(response);
  }

  @Test
  void intercept_shouldCallExecution_withCorrectArguments() throws IOException {
    // Given: MDC contains a trace ID
    MDC.put(MDC_TRACE_ID, "trace-123");
    byte[] body = "request-body".getBytes();

    // When: Interceptor processes the request
    interceptor.intercept(request, body, execution);

    // Then: Execution is called with the exact request and body
    verify(execution).execute(requestCaptor.capture(), bodyCaptor.capture());
    assertThat(requestCaptor.getValue()).isSameAs(request);
    assertThat(bodyCaptor.getValue()).isSameAs(body);
  }

  @Test
  void intercept_shouldOverwriteExistingHeader_whenTraceIdExists() throws IOException {
    // Given: Request already has an x-cdp-request-id header
    headers.set(TRACE_ID_HEADER, "old-trace-id");

    // And: MDC contains a different trace ID
    String newTraceId = "new-trace-id-67890";
    MDC.put(MDC_TRACE_ID, newTraceId);
    byte[] body = new byte[0];

    // When: Interceptor processes the request
    interceptor.intercept(request, body, execution);

    // Then: x-cdp-request-id header is replaced with the new trace ID
    assertThat(headers.get(TRACE_ID_HEADER)).containsExactly(newTraceId);
  }
}
