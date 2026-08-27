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
        // create → NOTIFICATION_CREATED (v1), submit → NOTIFICATION_SUBMITTED (v2)
        String referenceNumber = createAndSubmitNotification(TRACE_PREFIX + "001");

        int published = outboxPublishService.publishUnpublishedEvents();

        assertThat(published).isEqualTo(2);

        OutboxEvent submittedEvent = outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .findFirst().orElseThrow();
        assertThat(submittedEvent.getPublishedAt()).isNotNull();

        List<Message> messages = awaitSqsMessages(2);
        JsonNode snsEnvelope = snsEnvelopeByAggregateVersion(messages, 2L);
        JsonNode publishedMessage = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(publishedMessage.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(publishedMessage.get("eventId").asText()).isEqualTo(submittedEvent.getEventId());
        assertThat(publishedMessage.get("aggregateId").asText()).isEqualTo(submittedEvent.getAggregateId());
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
        // create → NOTIFICATION_CREATED (v1), submit → NOTIFICATION_SUBMITTED (v2)
        createAndSubmitNotification(TRACE_PREFIX + "002");
        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(2);

        purgeQueue();
        assertThat(outboxEventRepository.findAll()).allMatch(e -> e.getPublishedAt() != null);

        assertThat(outboxPublishService.publishUnpublishedEvents()).isZero();
        assertThat(receiveMessages()).isEmpty();
    }

    @Test
    void publishUnpublishedEvents_shouldPublishAggregateVersionsInOrder() throws Exception {
        // create → NOTIFICATION_CREATED (v1), submit → NOTIFICATION_SUBMITTED (v2)
        String referenceNumber = createAndSubmitNotification("trace-v1");
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        // direct status reset adds no outbox event; second submit → NOTIFICATION_SUBMITTED (v3)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-v2")
            .exchange()
            .expectStatus().isOk();

        List<OutboxEvent> unpublished = outboxEventRepository.findAll().stream()
            .filter(e -> e.getPublishedAt() == null)
            .toList();
        assertThat(unpublished).hasSize(3);

        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(3);

        List<OutboxEvent> publishedEvents = outboxEventRepository.findAll().stream()
            .sorted(Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(publishedEvents.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(publishedEvents.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(publishedEvents.get(2).getAggregateVersion()).isEqualTo(3L);
        assertThat(publishedEvents).allMatch(e -> e.getPublishedAt() != null);

        // v2 carries trace-v1 (first submit), v3 carries trace-v2 (second submit)
        List<Message> messages = awaitSqsMessages(3);
        JsonNode firstSubmitEnvelope = snsEnvelopeByAggregateVersion(messages, 2L);
        JsonNode secondSubmitEnvelope = snsEnvelopeByAggregateVersion(messages, 3L);
        assertThat(firstSubmitEnvelope.get("MessageAttributes").get("correlationId").get("Value").asText())
            .isEqualTo("trace-v1");
        assertThat(secondSubmitEnvelope.get("MessageAttributes").get("correlationId").get("Value").asText())
            .isEqualTo("trace-v2");

        JsonNode firstPayload = objectMapper.readTree(firstSubmitEnvelope.get("Message").asText());
        JsonNode secondPayload = objectMapper.readTree(secondSubmitEnvelope.get("Message").asText());
        assertThat(firstPayload.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(secondPayload.get("aggregateVersion").asLong()).isEqualTo(3L);
        assertThat(firstPayload.get("data").get("exchangedDocument").get("identifier").asText()).isEqualTo(referenceNumber);
        assertThat(secondPayload.get("data").get("exchangedDocument").get("identifier").asText()).isEqualTo(referenceNumber);
        assertThat(firstPayload.has("publishedAt")).isFalse();
        assertThat(secondPayload.has("publishedAt")).isFalse();
    }

    @Test
    void publishUnpublishedEvents_shouldDeliverNotificationEditedToSns() throws Exception {
        // create → NOTIFICATION_CREATED (v1), page save → NOTIFICATION_EDITED (v2)
        String referenceNumber = createAndSaveNotification(TRACE_PREFIX + "edited-001");

        int published = outboxPublishService.publishUnpublishedEvents();
        assertThat(published).isEqualTo(2);

        OutboxEvent editedEvent = outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType().endsWith("NotificationEdited"))
            .findFirst().orElseThrow();
        assertThat(editedEvent.getPublishedAt()).isNotNull();

        List<Message> messages = awaitSqsMessages(2);
        JsonNode snsEnvelope = snsEnvelopeByAggregateVersion(messages, 2L);
        JsonNode publishedMessage = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(publishedMessage.get("aggregateVersion").asLong()).isEqualTo(2L);
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
        // create → NOTIFICATION_CREATED (v1), page save → NOTIFICATION_EDITED (v2),
        // submit → NOTIFICATION_SUBMITTED (v3)
        String referenceNumber = createAndSaveNotification("trace-page-save");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-submit")
            .exchange()
            .expectStatus().isOk();

        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(3);

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
            .sorted(Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(events).hasSize(3);
        assertThat(events.get(0).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationCreated");
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationEdited");
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(events.get(2).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(events.get(2).getAggregateVersion()).isEqualTo(3L);
        assertThat(events).allMatch(e -> e.getPublishedAt() != null);
    }

}
