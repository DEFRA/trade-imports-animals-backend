package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.Origin;

public record ExchangedDocument(
    String identifier,
    String traderAssignedId,
    String notificationStatusCode,
    Integer versionId,
    String issueDateTime,
    TradeParty issuer,
    Authentication firstSignatoryAuthentication,
    List<ReferencedDocument> referenceDocument
) {

    private static final int VERSION_ID = 1;

    static ExchangedDocument from(NotificationAggregate notificationAggregate) {
        Notification notification = notificationAggregate.requireNotification();
        Origin origin = notification.getOrigin();
        return new ExchangedDocument(
            notificationAggregate.getReferenceNumber(),
            origin != null ? origin.getInternalReference() : null,
            notificationAggregate.getStatus() != null ? notificationAggregate.getStatus().name() : null,
            VERSION_ID,
            toUtcDateTime(notificationAggregate.getUpdated()),
            null,
            Authentication.from(notification),
            null);
    }

    private static String toUtcDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.toInstant(ZoneOffset.UTC).toString() : null;
    }
}
