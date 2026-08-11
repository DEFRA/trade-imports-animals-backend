package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;

/**
 * Spring Data interface projection over the merged {@code notification} collection that exposes
 * every notification-shape field the read API surfaces — the paginated list endpoint
 * ({@code GET /notifications?page&sort&referenceNumber}) and the single-ref endpoint
 * ({@code GET /notifications/{ref}}). Backs both today and any internal Java code that needs
 * notification-shape data.
 *
 * <p>Exposes the full notification-shape surface (all typed display fields from
 * {@link NotificationBase} plus the Mongo {@code _id}) so the mapper can populate
 * {@link NotificationResponse} without loading the opaque {@code fulfilments} payload. Server-only
 * lifecycle machinery ({@code submittedBaseline}, {@code submittedFulfilmentsBaseline},
 * {@code submittedAt}, {@code expireAt}) is intentionally excluded — it is not part of the
 * notification-shape read contract; the opaque fulfilments payload is excluded by design (that is
 * what the sibling {@link NotificationFulfilmentsView} projection is for).
 *
 * <p>Follows the pattern established by {@link NotificationReferenceOnly}.
 */
public interface NotificationView {

    String getId();

    String getReferenceNumber();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    LocalDateTime getUpdated();

    Origin getOrigin();

    Commodity getCommodity();

    String getReasonForImport();

    AdditionalDetails getAdditionalDetails();

    Operator getPlaceOfOrigin();

    Operator getConsignor();

    Operator getConsignee();

    Operator getImporter();

    Operator getDestination();

    Operator getConsignment();

    String getCphNumber();

    Transport getTransport();
}
