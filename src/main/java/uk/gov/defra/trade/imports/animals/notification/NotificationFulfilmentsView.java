package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import java.util.List;
import org.bson.Document;

/**
 * Spring Data interface projection over the {@code notification} collection: the reference number,
 * concurrency token, status, created and submitted timestamps, the opaque fulfilments payload and
 * whether an open amendment's answers differ from the submitted version.
 */
public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    Instant getCreated();

    Instant getSubmittedAt();

    List<Document> getFulfilments();

    Boolean getAnswersDifferFromSubmitted();
}
