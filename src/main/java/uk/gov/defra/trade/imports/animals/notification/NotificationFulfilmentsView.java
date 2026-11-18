package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import java.util.List;
import org.bson.Document;

public interface NotificationFulfilmentsView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    Instant getCreated();

    Instant getSubmittedAt();

    List<Document> getFulfilments();
}
