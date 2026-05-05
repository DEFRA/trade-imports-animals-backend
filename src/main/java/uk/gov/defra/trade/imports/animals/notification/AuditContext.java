package uk.gov.defra.trade.imports.animals.notification;

import java.util.Objects;

/**
 * Carries audit metadata across service-call boundaries so that downstream
 * operations (such as creating an {@link uk.gov.defra.trade.imports.animals.audit.Audit}
 * record) can attribute the action to the originating request and user.
 *
 * <p>Both components are sourced from required HTTP request headers on the
 * inbound controller call and are expected to be non-null and non-blank
 * when an instance is constructed. Callers must not pass {@code null} for
 * either component.
 *
 * @param traceId the CDP request correlation id, sourced from the
 *                {@code x-cdp-request-id} request header
 *                (see {@code NotificationController.HEADER_TRACE_ID}).
 *                Required, non-null.
 * @param userId  the authenticated user identifier, sourced from the
 *                {@code User-Id} request header
 *                (see {@code NotificationController.HEADER_USER_ID}).
 *                Required, non-null.
 */
public record AuditContext(String traceId, String userId) {
  public AuditContext {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(userId, "userId");
  }
}