package uk.gov.defra.trade.imports.animals.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Trader-facing notification routes require {@code Trade-Imports-Organisation-Id}.
 * Admin routes (bulk delete, replay, reference-number and outbox-event listing) are excluded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraderOrganisationHeaderFilter extends OncePerRequestFilter {

    public static final String ORGANISATION_ID_HEADER = "Trade-Imports-Organisation-Id";
    public static final String REQUEST_ORGANISATION_ID = "trade.imports.traderOrganisationId";
    private static final String MDC_ORGANISATION_ID = "organisationId";

    private final ObjectMapper objectMapper;

    public TraderOrganisationHeaderFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = servletPath(request);
        if (!path.startsWith("/notifications")) {
            return true;
        }
        String method = request.getMethod();
        if ("DELETE".equalsIgnoreCase(method) && "/notifications".equals(path)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && "/notifications/reference-numbers".equals(path)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.matches("/notifications/[^/]+/outbox-events")) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && path.matches("/notifications/[^/]+/replay")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        String organisationId = request.getHeader(ORGANISATION_ID_HEADER);
        if (organisationId == null || organisationId.isBlank()) {
            logAndReject(response, "Missing required header " + ORGANISATION_ID_HEADER);
            return;
        }

        request.setAttribute(REQUEST_ORGANISATION_ID, organisationId);
        try {
            MDC.put(MDC_ORGANISATION_ID, organisationId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_ORGANISATION_ID);
        }
    }

    private void logAndReject(HttpServletResponse response, String detail) throws IOException {
        org.slf4j.LoggerFactory.getLogger(TraderOrganisationHeaderFilter.class)
            .warn("Rejected trader notification request: {}", detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setType(java.net.URI.create("https://api.cdp.defra.cloud/problems/bad-request"));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
    }

    static String servletPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri != null ? uri : "";
    }
}
