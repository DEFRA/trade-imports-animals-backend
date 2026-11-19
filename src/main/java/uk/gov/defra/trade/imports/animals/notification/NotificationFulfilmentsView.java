package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.bson.Document;

@Value
@Builder
public class NotificationFulfilmentsView {

    String referenceNumber;

    Long concurrencyToken;

    NotificationStatus status;

    Instant created;

    Instant submittedAt;

    List<Document> fulfilments;

    boolean answersDifferFromSubmitted;

    public static NotificationFulfilmentsView from(NotificationAggregate notificationAggregate) {
        return NotificationFulfilmentsView.builder()
            .referenceNumber(notificationAggregate.getReferenceNumber())
            .concurrencyToken(notificationAggregate.getConcurrencyToken())
            .status(notificationAggregate.getStatus())
            .created(notificationAggregate.getCreated())
            .submittedAt(notificationAggregate.getSubmittedAt())
            .fulfilments(notificationAggregate.getFulfilments())
            .answersDifferFromSubmitted(notificationAggregate.isAnswersDifferFromSubmitted())
            .build();
    }
}
