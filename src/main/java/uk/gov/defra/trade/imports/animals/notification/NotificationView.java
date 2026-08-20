package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;

/**
 * Spring Data DTO projection backing the paginated dashboard read
 * ({@code GET /notifications?page&sort&referenceNumber}). A record rather than an interface
 * projection so Jackson can round-trip it symmetrically on the client side. Server-only fields
 * and the opaque {@code fulfilments} payload (served by {@link NotificationFulfilmentsView}) are
 * intentionally omitted so the read query doesn't load them.
 */
public record NotificationView(
    String referenceNumber,
    Long concurrencyToken,
    NotificationStatus status,
    LocalDateTime created,
    Origin origin,
    Commodity commodity,
    ConsignmentParty consignor,
    ConsignmentParty consignee,
    Transport transport) {
}
