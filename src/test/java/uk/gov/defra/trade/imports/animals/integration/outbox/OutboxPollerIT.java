package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.model.Message;
import uk.gov.defra.trade.imports.animals.notification.Notification;
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
    void publishUnpublishedEvents_shouldDeliverEventEnvelopeAndAttributesToSns() throws Exception {
        String referenceNumber = createAndSubmitNotificationWithActor(TRACE_PREFIX + "001");

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
        assertThat(publishedMessage.has("publishedAt")).isTrue();
        assertThat(Instant.parse(publishedMessage.get("publishedAt").asText()))
            .isEqualTo(event.getPublishedAt());
        assertThat(publishedMessage.get("publishedAt").asText())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

        JsonNode attributes = snsEnvelope.get("MessageAttributes");
        assertThat(attributes.get("eventType").get("Value").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(attributes.get("correlationId").get("Value").asText())
            .isEqualTo(TRACE_PREFIX + "001");
        assertThat(attributes.get("schemaVersion").get("Value").asText()).isEqualTo("1");
    }

    @Test
    void publishUnpublishedEvents_shouldIncludeActorAndStatusChangesInMessage() throws Exception {
        createAndSubmitNotificationWithActor(TRACE_PREFIX + "actor");

        outboxPublishService.publishUnpublishedEvents();

        Message sqsMessage = awaitSqsMessage();
        JsonNode snsEnvelope = objectMapper.readTree(sqsMessage.body());
        JsonNode publishedMessage = objectMapper.readTree(snsEnvelope.get("Message").asText());
        JsonNode actor = publishedMessage.get("actor");

        assertThat(actor.get("id").asText()).isEqualTo("contact-wire-001");
        assertThat(actor.get("source").asText()).isEqualTo("dynamics-contact");
        assertThat(actor.get("userType").asText()).isEqualTo("B2C");
        assertThat(actor.get("displayName").asText()).isEqualTo("Wire User");
        assertThat(actor.get("organisationId").asText()).isEqualTo("org-wire-001");
        assertThat(actor.has("onBehalfOfOrganisationId")).isFalse();
        assertThat(publishedMessage.get("statusChanges").size()).isEqualTo(1);
        assertThat(publishedMessage.get("statusChanges").get(0).get("status").asText())
            .isEqualTo("SUBMITTED");
        assertThat(publishedMessage.get("statusChanges").get(0).get("dateChanged")).isNotNull();
        assertThat(publishedMessage.get("statusChanges").get(0).get("actor")).isEqualTo(actor);
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
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        notification.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notification);

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
        assertThat(firstPayload.has("publishedAt")).isTrue();
        assertThat(secondPayload.has("publishedAt")).isTrue();
        assertThat(Instant.parse(firstPayload.get("publishedAt").asText()))
            .isEqualTo(publishedEvents.get(0).getPublishedAt());
        assertThat(Instant.parse(secondPayload.get("publishedAt").asText()))
            .isEqualTo(publishedEvents.get(1).getPublishedAt());
    }

    private String createAndSubmitNotificationWithActor(String traceId) {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(minimalNotificationDto())
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        Map<String, String> actor = Map.of(
            "id", "contact-wire-001",
            "source", "dynamics-contact",
            "userType", "B2C",
            "displayName", "Wire User",
            "organisationId", "org-wire-001");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, traceId)
            .bodyValue(actor)
            .exchange()
            .expectStatus().isOk();

        return referenceNumber;
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
