package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;

/**
 * Spring Data interface projection over the merged {@code notification} collection that exposes
 * the fields the fulfilment-view REST endpoint ({@code GET /notifications/{ref}/fulfilments})
 * returns: referenceNumber, status, dates, and the opaque fulfilments payload. Server-only fields
 * ({@code submittedFulfilmentsBaseline}, {@code submittedBaseline}, {@code expireAt}) are
 * intentionally excluded.
 *
 * <p>Follows the pattern established by {@link NotificationReferenceOnly}.
 */
public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    LocalDateTime getSubmittedAt();

    List<Document> getFulfilments();
}
