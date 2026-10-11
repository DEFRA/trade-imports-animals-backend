package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import java.util.List;
import org.bson.Document;

/**
 * Spring Data interface projection over the {@code notification} collection backing
 * {@code GET /notifications/{ref}/fulfilments}. Server-only fields
 * ({@code preAmendNotification}, {@code preAmendFulfilments}, {@code expireAt}) are
 * intentionally omitted so they aren't loaded on read. It does carry
 * {@code answersChangedSinceSubmission}: whether an amendment's content differs from its pre-amend
 * snapshot, without exposing the snapshot itself.
 */
public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    Instant getCreated();

    Instant getSubmittedAt();

    List<Document> getFulfilments();

    Boolean getAnswersChangedSinceSubmission();
}
