package uk.gov.defra.trade.imports.animals.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that populates MDC (Mapped Diagnostic Context) with request tracing information
 * for ECS (Elastic Common Schema) structured logging.
 *
 * Runs at HIGHEST_PRECEDENCE to ensure MDC is populated before any other filters or interceptors.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestTracingFilter implements Filter {

    private static final String MDC_TRACE_ID = "trace.id";
    private static final String MDC_HTTP_METHOD = "http.request.method";
    private static final String MDC_HTTP_STATUS = "http.response.status_code";
    private static final String MDC_URL_FULL = "url.full";

    @Value("${cdp.tracing.header-name}")
    private String header;
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // Extract trace ID from CDP request header (leave empty if not present)
            String traceId = httpRequest.getHeader(header);
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(MDC_TRACE_ID, traceId);
            }

            // Populate request metadata
            MDC.put(MDC_HTTP_METHOD, httpRequest.getMethod());
            MDC.put(MDC_URL_FULL, httpRequest.getRequestURL().toString());

            log.debug("{} {}", httpRequest.getMethod(), httpRequest.getRequestURL().toString());

            // Execute filter chain
            chain.doFilter(request, response);

            // Capture response status after chain completes
            if (response instanceof HttpServletResponse httpResponse) {
                MDC.put(MDC_HTTP_STATUS, String.valueOf(httpResponse.getStatus()));
                log.debug("Response status {} for {}", httpResponse.getStatus(), httpRequest.getRequestURI());
            }

        } finally {
            // Critical: Clear MDC to prevent data leakage across requests in thread pool
            MDC.clear();
        }
    }
}
