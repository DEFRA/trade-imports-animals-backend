package uk.gov.defra.trade.imports.animals.filter;

import jakarta.servlet.http.HttpServletRequest;

public final class AdminNotificationRoutes {

    private AdminNotificationRoutes() {
    }

    public static boolean isAdminNotificationRoute(HttpServletRequest request) {
        String path = pathWithinApplication(request);
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

    static String pathWithinApplication(HttpServletRequest request) {
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
