package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

    private final OutboxEventRepository outboxEventRepository;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final OutboxConfig outboxConfig;

    /**
     * Publishes a batch of unpublished outbox events to SNS in aggregate version order.
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
        List<OutboxEvent> events = outboxEventRepository
            .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(
                PageRequest.of(0, batchSize));

        int published = 0;
        for (OutboxEvent event : events) {
            if (event.getData() == null) {
                log.error(
                    "Skipping outbox event with null payload: eventId={} aggregateId={} version={}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion());
                continue;
            }
            try {
                publishToSns(event, topicArn);
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
                published++;
            } catch (JsonProcessingException e) {
                log.error(
                    "Outbox event payload is not serializable; manual investigation required: "
                        + "eventId={} aggregateId={} version={}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion(), e);
                break;
            } catch (SnsException e) {
                log.error("Failed to publish outbox event eventId={} aggregateId={} version={}: {}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion(),
                    e.getMessage(), e);
                break;
            }
        }
        return published;
    }

    void publishToSns(OutboxEvent event, String topicArn) throws JsonProcessingException {
        String messageBody = objectMapper.writeValueAsString(event.getData());
        PublishRequest.Builder requestBuilder = PublishRequest.builder()
            .topicArn(topicArn)
            .message(messageBody)
            .messageAttributes(buildMessageAttributes(event));
        if (topicArn.endsWith(".fifo")) {
            requestBuilder
                .messageGroupId(event.getAggregateId())
                .messageDeduplicationId(event.getEventId());
        }
        snsClient.publish(requestBuilder.build());
        log.info("Published outbox event eventId={} aggregateId={} version={}",
            event.getEventId(), event.getAggregateId(), event.getAggregateVersion());
    }

    static Map<String, MessageAttributeValue> buildMessageAttributes(OutboxEvent event) {
        OutboxEventMetadata metadata = event.getMetadata();
        String correlationId = metadata != null ? metadata.getCorrelationId() : "";
        String schemaVersion = metadata != null ? metadata.getSchemaVersion() : "";
        return Map.of(
            ATTR_EVENT_TYPE, stringAttribute(event.getEventType()),
            ATTR_CORRELATION_ID, stringAttribute(correlationId),
            ATTR_SCHEMA_VERSION, stringAttribute(schemaVersion));
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value != null ? value : "")
            .build();
    }
}
