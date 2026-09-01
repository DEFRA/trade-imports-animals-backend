package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.model.Message;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.ReplayResponse;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxPublishService;

/**
 * End-to-end integration test for the admin-triggered replay endpoint against Floci.
 */
class ReplayIT extends OutboxIntegrationBase {

    private static final String ADMIN_SECRET_HEADER = "Trade-Imports-Animals-Admin-Secret";
    private static final String VALID_ADMIN_SECRET = "test-admin-secret";

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private OutboxPublishService outboxPublishService;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        auditRepository.deleteAll();
        purgeQueue();
    }

    @Test
    void replay_shouldRepublishEventToSns_andNotMutateOutboxPublishedAt() throws Exception {
        // Given — create + submit (CREATED v1, SUBMITTED v2), then publish both normally
        String referenceNumber = createAndSubmitNotification("trace-replay-001");
        outboxPublishService.publishUnpublishedEvents();

        OutboxEvent submittedEvent = findSubmittedEvent();
        Instant originalPublishedAt = submittedEvent.getPublishedAt();
        assertThat(originalPublishedAt).isNotNull();

        purgeQueue();

        // When — replay all events for this notification (CREATED + SUBMITTED = 2)
        ReplayResponse response = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header(HEADER_TRACE_ID, "trace-replay-001")
            .header("User-Id", "user-replay-001")
            .exchange()
            .expectStatus().isOk()
            .expectBody(ReplayResponse.class)
            .returnResult().getResponseBody();

        // Then — 2 events replayed, delivered to SQS
        assertThat(response).isNotNull();
        assertThat(response.eventsReplayed()).isEqualTo(2);

        List<Message> messages = awaitSqsMessages(2);
        JsonNode snsEnvelope = snsEnvelopeByAggregateVersion(messages, 2L);
        JsonNode payload = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(payload.get("eventId").asText()).isEqualTo(submittedEvent.getEventId());
        assertThat(payload.get("aggregateVersion").asLong()).isEqualTo(2L);

        // publishedAt on the outbox event is NOT mutated by replay
        OutboxEvent eventAfterReplay = findSubmittedEvent();
        assertThat(eventAfterReplay.getPublishedAt()).isEqualTo(originalPublishedAt);
    }

    @Test
    void replay_shouldRepublishMultipleEventsInVersionOrder() throws Exception {
        // Given — create (v1), submit (v2), direct DRAFT reset, submit again (v3)
        String referenceNumber = createAndSubmitNotification("trace-v1");
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-v2")
            .exchange().expectStatus().isOk();

        assertThat(outboxEventRepository.count()).isEqualTo(3);

        // When
        ReplayResponse response = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header(HEADER_TRACE_ID, "trace-replay-multi")
            .header("User-Id", "user-replay-multi")
            .exchange()
            .expectStatus().isOk()
            .expectBody(ReplayResponse.class)
            .returnResult().getResponseBody();

        // Then — all 3 events replayed and delivered to SQS
        assertThat(response).isNotNull();
        assertThat(response.eventsReplayed()).isEqualTo(3);

        List<Message> messages = awaitSqsMessages(3);
        assertThat(messages).hasSize(3);
        for (Message message : messages) {
            JsonNode payload = objectMapper.readTree(
                objectMapper.readTree(message.body()).get("Message").asText());
            assertThat(payload.get("data").get("exchangedDocument").get("identifier").asText())
                .isEqualTo(referenceNumber);
        }
    }

    @Test
    void replay_shouldWriteAuditRecord() {
        // create → NOTIFICATION_CREATED (v1), submit → NOTIFICATION_SUBMITTED (v2)
        String referenceNumber = createAndSubmitNotification("trace-audit");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header(HEADER_TRACE_ID, "trace-audit")
            .header("User-Id", "user-audit-001")
            .exchange()
            .expectStatus().isOk();

        List<Audit> audits = auditRepository.findAll();
        assertThat(audits).hasSize(1);
        Audit audit = audits.getFirst();
        assertThat(audit.getAction()).isEqualTo(Action.REPLAY_EVENTS);
        assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(audit.getNotificationReferenceNumbers()).containsExactly(referenceNumber);
        assertThat(audit.getNumberOfNotifications()).isEqualTo(1);
        assertThat(audit.getNumberOfEvents()).isEqualTo(2);
        assertThat(audit.getTraceId()).isEqualTo("trace-audit");
        assertThat(audit.getUserId()).isEqualTo("user-audit-001");
        assertThat(audit.getTimestamp()).isNotNull();
    }

    @Test
    void replay_shouldReturn404_whenNoOutboxEventsExist() {
        // Given — notification exists but outbox events have been cleared (no events to replay)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(minimalNotificationDto()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // Clear the NOTIFICATION_CREATED event so the replay endpoint has nothing to replay
        outboxEventRepository.deleteAll();
        assertThat(outboxEventRepository.count()).isZero();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header(HEADER_TRACE_ID, "trace-404")
            .header("User-Id", "user-404")
            .exchange()
            .expectStatus().isNotFound();

        assertThat(auditRepository.findAll()).isEmpty();
    }

    private OutboxEvent findSubmittedEvent() {
        return outboxEventRepository.findAll().stream()
            .filter(e -> e.getEventType().endsWith("NotificationSubmitted"))
            .findFirst().orElseThrow();
    }

    @Test
    void replay_shouldReturn401_whenAdminSecretIsMissing() {
        String referenceNumber = createAndSubmitNotification("trace-401");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-401")
            .header("User-Id", "user-401")
            .exchange()
            .expectStatus().isUnauthorized();

        assertThat(auditRepository.findAll()).isEmpty();
    }
}
