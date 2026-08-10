package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;

/**
 * Spring Data interface projection over the merged {@code notification} collection that exposes
 * the notification-shape display fields consumed by the temporary dashboard endpoint
 * ({@code GET /notifications?page&sort&referenceNumber}) and the single-ref endpoint
 * ({@code GET /notifications/{ref}}, if kept).
 *
 * <p>YAGNI-trimmed to the fields the frontend list marshaller actually reads (see
 * {@code marshal/list-item.js}): {@code referenceNumber}, {@code status}, {@code created},
 * plus the nested display objects {@code commodity}, {@code origin}, {@code transport},
 * {@code consignor}, {@code consignee}. Fields on {@code NotificationBase} that no consumer
 * currently reads ({@code updated}, {@code reasonForImport}, {@code additionalDetails},
 * {@code placeOfOrigin}, {@code importer}, {@code destination}, {@code consignment},
 * {@code cphNumber}) are intentionally excluded; the opaque fulfilments payload and server-only
 * snapshots are excluded by design (this is the notification-shape view).
 *
 * <p>Follows the pattern established by {@link NotificationReferenceOnly}.
 */
public interface NotificationView {

    String getReferenceNumber();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    Commodity getCommodity();

    Origin getOrigin();

    Transport getTransport();

    Operator getConsignor();

    Operator getConsignee();
}
