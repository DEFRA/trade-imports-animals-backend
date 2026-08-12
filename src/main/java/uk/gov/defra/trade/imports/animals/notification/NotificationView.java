package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;

/**
 * Spring Data DTO projection over the {@code notification} collection that exposes only the fields
 * the paginated list endpoint ({@code GET /notifications?page&sort&referenceNumber}) surfaces to
 * the dashboard. Serialized directly as the list-item wire shape — no intermediate DTO — so the
 * response carries exactly these eight fields, and Jackson can deserialize it symmetrically on
 * the client side because the type is a concrete record (not an interface projection).
 *
 * <p>Anything else on the notification (server-only lifecycle machinery, other display fields no
 * client reads today, the opaque {@code fulfilments} payload served by {@link NotificationFulfilmentsView})
 * is deliberately excluded so the read query does not load it.
 */
public record NotificationView(
    String referenceNumber,
    NotificationStatus status,
    LocalDateTime created,
    Origin origin,
    Commodity commodity,
    Operator consignor,
    Operator consignee,
    Transport transport) {
}
