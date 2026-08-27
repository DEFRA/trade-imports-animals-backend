package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.model.Message;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxPublishService;

/**
 * End-to-end integration test for the outbox SNS relay against Floci.
 */
class OutboxPollerIT extends OutboxIntegrationBase {

    private static final String TRACE_PREFIX = "trace-outbox-it-";

    @Autowired
    private OutboxPublishService outboxPublishService;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        purgeQueue();
    }

    @Test
    void publishUnpublishedEvents_shouldDeliverToSnsAndMarkPublishedAt() throws Exception {
        String referenceNumber = createAndSubmitNotification(TRACE_PREFIX + "001");

        int published = outboxPublishService.publishUnpublishedEvents();

        assertThat(published).isEqualTo(1);

        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getPublishedAt()).isNotNull();

        Message sqsMessage = awaitSqsMessage();
        JsonNode snsEnvelope = objectMapper.readTree(sqsMessage.body());
        JsonNode publishedMessage = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(publishedMessage.get("aggregateVersion").asLong()).isEqualTo(1L);
        assertThat(publishedMessage.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(publishedMessage.get("aggregateId").asText()).isEqualTo(event.getAggregateId());
        assertThat(publishedMessage.get("aggregateType").asText()).isEqualTo("Notification");
        assertThat(publishedMessage.get("subType").asText()).isEqualTo("GBN-AG");
        assertThat(publishedMessage.get("eventType").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(publishedMessage.get("timestamp")).isNotNull();
        assertThat(publishedMessage.get("metadata").get("correlationId").asText())
            .isEqualTo(TRACE_PREFIX + "001");
        assertThat(publishedMessage.get("metadata").get("schemaVersion").asText()).isEqualTo("1");
        assertThat(publishedMessage.get("data").get("exchangedDocument").get("identifier").asText()).isEqualTo(referenceNumber);
        assertThat(publishedMessage.has("publishedAt")).isFalse();

        JsonNode attributes = snsEnvelope.get("MessageAttributes");
        assertThat(attributes.get("eventType").get("Value").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(attributes.get("correlationId").get("Value").asText())
            .isEqualTo(TRACE_PREFIX + "001");
        assertThat(attributes.get("schemaVersion").get("Value").asText()).isEqualTo("1");
    }

    @Test
    void publishUnpublishedEvents_shouldNotRepublishAlreadyPublishedEvents() {
        createAndSubmitNotification(TRACE_PREFIX + "002");
        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(1);

        purgeQueue();
        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getPublishedAt()).isNotNull();

        assertThat(outboxPublishService.publishUnpublishedEvents()).isZero();
        assertThat(receiveMessages()).isEmpty();
    }

    @Test
    void publishUnpublishedEvents_shouldPublishAggregateVersionsInOrder() throws Exception {
        String referenceNumber = createAndSubmitNotification("trace-v1");
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-v2")
            .exchange()
            .expectStatus().isOk();

        List<OutboxEvent> unpublished = outboxEventRepository.findAll().stream()
            .filter(e -> e.getPublishedAt() == null)
            .toList();
        assertThat(unpublished).hasSize(2);

        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(2);

        List<OutboxEvent> publishedEvents = outboxEventRepository.findAll().stream()
            .sorted(Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(publishedEvents.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(publishedEvents.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(publishedEvents).allMatch(e -> e.getPublishedAt() != null);

        List<Message> messages = awaitSqsMessages(2);
        JsonNode firstEnvelope = snsEnvelopeByAggregateVersion(messages, 1L);
        JsonNode secondEnvelope = snsEnvelopeByAggregateVersion(messages, 2L);
        assertThat(firstEnvelope.get("MessageAttributes").get("correlationId").get("Value").asText())
            .isEqualTo("trace-v1");
        assertThat(secondEnvelope.get("MessageAttributes").get("correlationId").get("Value").asText())
            .isEqualTo("trace-v2");

        JsonNode firstPayload = objectMapper.readTree(firstEnvelope.get("Message").asText());
        JsonNode secondPayload = objectMapper.readTree(secondEnvelope.get("Message").asText());
        assertThat(firstPayload.get("aggregateVersion").asLong()).isEqualTo(1L);
        assertThat(secondPayload.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(firstPayload.get("data").get("exchangedDocument").get("identifier").asText()).isEqualTo(referenceNumber);
        assertThat(secondPayload.get("data").get("exchangedDocument").get("identifier").asText()).isEqualTo(referenceNumber);
        assertThat(firstPayload.has("publishedAt")).isFalse();
        assertThat(secondPayload.has("publishedAt")).isFalse();
    }

    @Test
    void publishUnpublishedEvents_shouldDeliverNotificationEditedToSns() throws Exception {
        String referenceNumber = createAndSaveNotification(TRACE_PREFIX + "edited-001");

        int published = outboxPublishService.publishUnpublishedEvents();
        assertThat(published).isEqualTo(1);

        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getPublishedAt()).isNotNull();

        Message sqsMessage = awaitSqsMessage();
        JsonNode snsEnvelope = objectMapper.readTree(sqsMessage.body());
        JsonNode publishedMessage = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(publishedMessage.get("aggregateVersion").asLong()).isEqualTo(1L);
        assertThat(publishedMessage.get("eventType").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationEdited");
        assertThat(publishedMessage.get("metadata").get("correlationId").asText())
            .isEqualTo(TRACE_PREFIX + "edited-001");
        assertThat(publishedMessage.get("data").get("exchangedDocument").get("identifier").asText())
            .isEqualTo(referenceNumber);
        assertThat(publishedMessage.has("publishedAt")).isFalse();

        JsonNode attributes = snsEnvelope.get("MessageAttributes");
        assertThat(attributes.get("eventType").get("Value").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationEdited");
        assertThat(attributes.get("correlationId").get("Value").asText())
            .isEqualTo(TRACE_PREFIX + "edited-001");
    }

    @Test
    void publishUnpublishedEvents_shouldIncrementAggregateVersion_acrossPageSaveAndSubmit() throws Exception {
        // Page save emits NotificationEdited (v1), submit emits NotificationSubmitted (v2)
        String referenceNumber = createAndSaveNotification("trace-page-save");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-submit")
            .exchange()
            .expectStatus().isOk();

        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(2);

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
            .sorted(Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationEdited");
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(events).allMatch(e -> e.getPublishedAt() != null);
    }

    private JsonNode snsEnvelopeByAggregateVersion(List<Message> messages, long aggregateVersion)
        throws Exception {
        for (Message message : messages) {
            JsonNode snsEnvelope = objectMapper.readTree(message.body());
            JsonNode payload = objectMapper.readTree(snsEnvelope.get("Message").asText());
            if (payload.get("aggregateVersion").asLong() == aggregateVersion) {
                return snsEnvelope;
            }
        }
        throw new AssertionError("No SNS message found for aggregateVersion " + aggregateVersion);
    }
}
