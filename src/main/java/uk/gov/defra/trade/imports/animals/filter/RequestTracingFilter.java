package uk.gov.defra.trade.imports.animals.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestTracingFilter implements Filter {

    public static final String TRACE_ID_HEADER = "x-cdp-request-id";
    public static final String MDC_TRACE_ID = "trace.id";

    private static final String MDC_HTTP_METHOD = "http.request.method";
    private static final String MDC_HTTP_STATUS = "http.response.status_code";
    private static final String MDC_URL_FULL = "url.full";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            putTraceIdInMdc(httpRequest);
            final String method = httpRequest.getMethod();
            final String url = httpRequest.getRequestURL().toString();
            MDC.put(MDC_HTTP_METHOD, method);
            MDC.put(MDC_URL_FULL, url);

            log.debug("{} {}", method, url);

            chain.doFilter(request, response);

            if (response instanceof HttpServletResponse httpResponse) {
                MDC.put(MDC_HTTP_STATUS, String.valueOf(httpResponse.getStatus()));
                log.debug("Response status {} for {}", httpResponse.getStatus(), url);
            }

        } finally {
            MDC.clear();
        }
    }

    private static void putTraceIdInMdc(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
    }
}
