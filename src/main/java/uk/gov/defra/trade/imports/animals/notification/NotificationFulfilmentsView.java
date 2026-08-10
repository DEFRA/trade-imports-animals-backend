package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;

/**
 * Spring Data interface projection over the merged {@code notification} collection that exposes
 * the fields the fulfilment-view REST endpoint ({@code GET /notification-fulfilments/{id}}) returns:
 * id, status, dates, and the opaque fulfilments payload. Server-only fields
 * ({@code submittedFulfilmentsBaseline}, {@code submittedBaseline}, {@code expireAt}) are
 * intentionally excluded.
 *
 * <p>Two wire-shape preservations via {@link Value} keep today's response body byte-compatible with
 * the retired {@code NotificationFulfilments} shape, so the frontend response marshaller (see
 * {@code marshal/document.js}) keeps working unchanged:
 * <ul>
 *   <li>{@code getId()} maps to the merged entity's {@code referenceNumber} (not the Mongo
 *       {@code _id}) — today's {@code NotificationFulfilments.id} was always the reference number.
 *   <li>{@code getCreatedAt()} maps to the merged entity's {@code created} (inherited from
 *       {@code NotificationBase}) — today's fulfilment response field name was {@code createdAt}.
 * </ul>
 *
 * <p>Follows the pattern established by {@link NotificationReferenceOnly}.
 */
public interface NotificationFulfilmentsView {

    @Value("#{target.referenceNumber}")
    String getId();

    NotificationStatus getStatus();

    @Value("#{target.created}")
    LocalDateTime getCreatedAt();

    LocalDateTime getSubmittedAt();

    List<Document> getFulfilments();
}
