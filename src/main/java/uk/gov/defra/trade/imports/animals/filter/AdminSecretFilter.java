package uk.gov.defra.trade.imports.animals.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that protects privileged endpoints by requiring a shared secret header.
 * Applied only to DELETE /notifications requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AdminSecretFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "Trade-Imports-Animals-Admin-Secret";

    @Value("${admin.secret}")
    private String tradeImportsAnimalsAdminSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException, ServletException {
        String providedSecret = request.getHeader(HEADER_NAME);

        if (tradeImportsAnimalsAdminSecret.isBlank()
                || providedSecret == null
                || !MessageDigest.isEqual(
                    tradeImportsAnimalsAdminSecret.getBytes(StandardCharsets.UTF_8),
                    providedSecret.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("DELETE".equalsIgnoreCase(request.getMethod())
            && request.getRequestURI().startsWith("/notifications"));
    }
}
