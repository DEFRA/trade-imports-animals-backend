package uk.gov.defra.trade.imports.animals.outbox;

import java.time.Instant;
import java.util.Map;

/**
 * SNS message body for a published outbox event — the full envelope plus {@code data},
 * excluding internal persistence fields such as {@code publishedAt}.
 */
public record OutboxPublishedMessage(
    String eventId,
    String aggregateId,
    String aggregateType,
    String subType,
    long aggregateVersion,
    String eventType,
    Instant timestamp,
    OutboxEventMetadata metadata,
    Map<String, Object> data) {

    static OutboxPublishedMessage from(OutboxEvent event) {
        return new OutboxPublishedMessage(
            event.getEventId(),
            event.getAggregateId(),
            event.getAggregateType(),
            event.getSubType(),
            event.getAggregateVersion(),
            event.getEventType(),
            event.getTimestamp(),
            event.getMetadata(),
            event.getData());
    }
}
