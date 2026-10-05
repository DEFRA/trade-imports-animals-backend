package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.Origin;

@JsonInclude(JsonInclude.Include.NON_NULL)
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

    static ExchangedDocument from(
        NotificationAggregate notificationAggregate,
        Integer versionId,
        List<AccompanyingDocument> accompanyingDocuments) {
        Notification notification = notificationAggregate.requireNotification();
        Origin origin = notification.getOrigin();
        return new ExchangedDocument(
            notificationAggregate.getReferenceNumber(),
            origin != null ? origin.getInternalReference() : null,
            notificationAggregate.getStatus() != null ? notificationAggregate.getStatus().name() : null,
            versionId,
            toUtcDateTime(notificationAggregate.getUpdated()),
            null,
            Authentication.from(notification),
            referenceDocuments(accompanyingDocuments));
    }

    // No documents means no referenceDocument key, never an empty array.
    @SuppressWarnings("java:S1168")
    private static List<ReferencedDocument> referenceDocuments(List<AccompanyingDocument> accompanyingDocuments) {
        if (accompanyingDocuments == null || accompanyingDocuments.isEmpty()) {
            return null;
        }
        return accompanyingDocuments.stream().map(ReferencedDocument::accompanyingDocument).toList();
    }

    /**
     * {@code Instant.toString()} is ISO-8601 UTC, which is byte-identical to what the previous
     * {@code LocalDateTime.toInstant(ZoneOffset.UTC).toString()} produced for the same moment.
     * Sub-millisecond digits are deliberately preserved — PIMS receives the value unchanged.
     */
    private static String toUtcDateTime(Instant dateTime) {
        return dateTime != null ? dateTime.toString() : null;
    }
}
