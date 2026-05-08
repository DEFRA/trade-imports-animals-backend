package uk.gov.defra.trade.imports.animals.notification;

import java.util.Objects;

/**
 * Audit metadata carried across service-call boundaries so downstream operations (e.g. creating an
 * {@link uk.gov.defra.trade.imports.animals.audit.Audit} record) can attribute an action to the
 * originating request and user.
 *
 * @param traceId the CDP request correlation id (from the {@code x-cdp-request-id} header)
 * @param userId  the authenticated user identifier (from the {@code User-Id} header)
 */
public record AuditContext(String traceId, String userId) {
  public AuditContext {
    Objects.requireNonNull(traceId, "traceId");
    Objects.requireNonNull(userId, "userId");
  }
}
