package uk.gov.defra.trade.imports.animals.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.defra.trade.imports.animals.exceptions.ProblemDetailsFactory;

/**
 * Trader-facing notification and document-upload routes require
 * {@code Trade-Imports-Organisation-Id}. Admin notification routes and cdp-uploader scan callbacks
 * are excluded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@Slf4j
@RequiredArgsConstructor
public class TraderOrganisationHeaderFilter extends OncePerRequestFilter {

    public static final String ORGANISATION_ID_HEADER = "Trade-Imports-Organisation-Id";
    private static final String MDC_ORGANISATION_ID = "organisationId";
    private static final URI TYPE_BAD_REQUEST =
        URI.create("https://api.cdp.defra.cloud/problems/bad-request");

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (AdminNotificationRoutes.isAdminNotificationRoute(request)) {
            return true;
        }
        String path = pathWithinApplication(request);
        if (path.startsWith("/notifications")) {
            return false;
        }
        if (path.startsWith("/document-uploads") && !path.endsWith("/scan-results")) {
            return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        String organisationId = request.getHeader(ORGANISATION_ID_HEADER);
        if (organisationId == null || organisationId.isBlank()) {
            rejectMissingHeader(response);
            return;
        }

        try {
            MDC.put(MDC_ORGANISATION_ID, organisationId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_ORGANISATION_ID);
        }
    }

    private void rejectMissingHeader(HttpServletResponse response) throws IOException {
        String detail = "Missing required header " + ORGANISATION_ID_HEADER;
        ProblemDetail problem =
            ProblemDetailsFactory.problemDetail(
                HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "Bad Request", detail);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        return AdminNotificationRoutes.pathWithinApplication(request);
    }
}
