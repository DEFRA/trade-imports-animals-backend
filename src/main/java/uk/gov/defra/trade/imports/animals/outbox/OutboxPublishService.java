package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SnsException;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPublishService {

    static final String ATTR_EVENT_TYPE = "eventType";
    static final String ATTR_CORRELATION_ID = "correlationId";
    static final String ATTR_SCHEMA_VERSION = "schemaVersion";
    static final String ATTR_SCHEMA_URL = "schemaUrl";

    private final OutboxEventRepository outboxEventRepository;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final OutboxConfig outboxConfig;

    /**
     * Publishes unpublished outbox events to SNS in the order they were written, taking further
     * batches until the backlog is drained, a publish fails, or
     * {@code outbox.poller.max-events-per-run} is reached.
     *
     * @return number of events successfully published in this run
     */
    public int publishUnpublishedEvents() {
        String topicArn = outboxConfig.sns().topicArn();
        if (topicArn == null || topicArn.isBlank()) {
            log.debug("Outbox SNS topic ARN not configured; skipping publish");
            return 0;
        }

        int batchSize = outboxConfig.poller().batchSize();
        int maxEventsPerRun = outboxConfig.poller().maxEventsPerRun();

        int published = 0;
        int handled = 0;
        while (handled < maxEventsPerRun) {
            int pageSize = Math.min(batchSize, maxEventsPerRun - handled);
            List<OutboxEvent> events = outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(
                    PageRequest.of(0, pageSize));
            if (events.isEmpty()) {
                break;
            }
            BatchOutcome outcome = publishBatch(events, topicArn);
            published += outcome.published();
            handled += outcome.published() + outcome.skipped();
            if (outcome.stoppedOnError() || events.size() < pageSize) {
                break;
            }
        }
        return published;
    }

    private BatchOutcome publishBatch(List<OutboxEvent> events, String topicArn) {
        int published = 0;
        int skipped = 0;
        boolean stoppedOnError = false;
        for (OutboxEvent event : events) {
            if (event.getData() == null) {
                log.error(
                    "Skipping outbox event with null payload: eventId={} aggregateId={} version={}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion());
                skipped++;
                continue;
            }
            try {
                Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                event.setPublishedAt(publishedAt);
                publishToSns(event, topicArn);
                outboxEventRepository.save(event);
                published++;
            } catch (JsonProcessingException e) {
                event.setPublishedAt(null);
                log.error(
                    "Outbox event payload is not serializable; manual investigation required: "
                        + "eventId={} aggregateId={} version={}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion(), e);
                stoppedOnError = true;
                break;
            } catch (SnsException e) {
                event.setPublishedAt(null);
                log.error("Failed to publish outbox event eventId={} aggregateId={} version={}: {}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion(),
                    e.getMessage(), e);
                stoppedOnError = true;
                break;
            }
        }
        return new BatchOutcome(published, skipped, stoppedOnError);
    }

    private record BatchOutcome(int published, int skipped, boolean stoppedOnError) {}

    void publishToSns(OutboxEvent event, String topicArn) throws JsonProcessingException {
        String messageBody = objectMapper.writeValueAsString(event);
        snsClient.publish(PublishRequest.builder()
            .topicArn(topicArn)
            .message(messageBody)
            .messageAttributes(buildMessageAttributes(event))
            .messageGroupId(event.getAggregateId())
            .messageDeduplicationId(event.getEventId())
            .build());
        log.info("Published outbox event eventId={} aggregateId={} version={}",
            event.getEventId(), event.getAggregateId(), event.getAggregateVersion());
    }

    static Map<String, MessageAttributeValue> buildMessageAttributes(OutboxEvent event) {
        OutboxEventMetadata metadata = event.getMetadata();
        String correlationId = metadata != null ? metadata.getCorrelationId() : "";
        String schemaVersion = metadata != null ? metadata.getSchemaVersion() : "";
        String schemaUrl = metadata != null ? metadata.getSchemaUrl() : "";
        return Map.of(
            ATTR_EVENT_TYPE, stringAttribute(event.getEventType()),
            ATTR_CORRELATION_ID, stringAttribute(correlationId),
            ATTR_SCHEMA_VERSION, stringAttribute(schemaVersion),
            ATTR_SCHEMA_URL, stringAttribute(schemaUrl));
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value != null ? value : "")
            .build();
    }
}
