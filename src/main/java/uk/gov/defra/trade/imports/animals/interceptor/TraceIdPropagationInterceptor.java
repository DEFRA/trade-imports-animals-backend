package uk.gov.defra.trade.imports.animals.interceptor;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * HTTP client interceptor that propagates the x-cdp-request-id trace header to all outbound HTTP
 * requests.
 *
 * <p>This interceptor retrieves the trace ID from the SLF4J MDC context (set by
 * RequestTracingFilter) and adds it as the x-cdp-request-id header to every outbound HTTP call made
 * via RestClient or RestTemplate.
 *
 * <p>CDP Requirement: All outbound HTTP calls must include the x-cdp-request-id header for
 * distributed tracing across service boundaries.
 */
@Component
public class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

  private static final String MDC_TRACE_ID = "trace.id";

  private final String headerName;
  
  public TraceIdPropagationInterceptor(@Value("${cdp.tracing.header-name}") String headerName) {
      this.headerName = headerName;
  }
  
  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String traceId = MDC.get(MDC_TRACE_ID);
    if (traceId != null && !traceId.isBlank()) {
      request.getHeaders().set(headerName, traceId);
    }
    return execution.execute(request, body);
  }
}
