package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;

/**
 * Spring Data interface projection over the {@code notification} collection backing
 * {@code GET /notifications/{ref}/fulfilments}. Server-only fields
 * ({@code submittedFulfilmentsBaseline}, {@code submittedBaseline}, {@code expireAt}) are
 * intentionally omitted so they aren't loaded on read.
 */
public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    LocalDateTime getSubmittedAt();

    List<Document> getFulfilments();
}
