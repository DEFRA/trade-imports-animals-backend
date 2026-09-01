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
    void replay_shouldRepublishEventToSns() throws Exception {
        // Given — an outbox event the poller has not published, so the replay is not a duplicate
        String referenceNumber = createAndSubmitNotification("trace-replay-001");
        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getPublishedAt()).isNull();

        // When
        ReplayResponse response = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header(HEADER_TRACE_ID, "trace-replay-001")
            .header("User-Id", "user-replay-001")
            .exchange()
            .expectStatus().isOk()
            .expectBody(ReplayResponse.class)
            .returnResult().getResponseBody();

        // Then — 1 event replayed, delivered to SQS
        assertThat(response).isNotNull();
        assertThat(response.eventsReplayed()).isEqualTo(1);

        Message sqsMessage = awaitSqsMessage();
        JsonNode snsEnvelope = objectMapper.readTree(sqsMessage.body());
        JsonNode payload = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(payload.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(payload.get("aggregateVersion").asLong()).isEqualTo(1L);
    }

    /**
     * Delivery is asserted separately in {@link #replay_shouldRepublishEventToSns()}: replaying an
     * already-published event reuses its eventId as the deduplication id, so SNS FIFO suppresses
     * the message for five minutes. Only the outbox state is assertable here.
     */
    @Test
    void replay_shouldNotMutateOutboxPublishedAt() {
        // Given — submit a notification so an outbox event exists, then publish it normally
        String referenceNumber = createAndSubmitNotification("trace-replay-002");
        outboxPublishService.publishUnpublishedEvents();

        Instant originalPublishedAt = outboxEventRepository.findAll().getFirst().getPublishedAt();
        assertThat(originalPublishedAt).isNotNull();

        // When
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/replay", referenceNumber)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header(HEADER_TRACE_ID, "trace-replay-002")
            .header("User-Id", "user-replay-002")
            .exchange()
            .expectStatus().isOk();

        // Then — publishedAt on the outbox event is NOT mutated by replay
        OutboxEvent eventAfterReplay = outboxEventRepository.findAll().getFirst();
        assertThat(eventAfterReplay.getPublishedAt()).isEqualTo(originalPublishedAt);
    }

    @Test
    void replay_shouldRepublishMultipleEventsInVersionOrder() throws Exception {
        // Given — two outbox events (submit, reset to DRAFT, submit again)
        String referenceNumber = createAndSubmitNotification("trace-v1");
        NotificationAggregate notificationAggregate = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notificationAggregate);

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-v2")
            .exchange().expectStatus().isOk();

        assertThat(outboxEventRepository.count()).isEqualTo(2);

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

        // Then — both events delivered to SQS
        assertThat(response).isNotNull();
        assertThat(response.eventsReplayed()).isEqualTo(2);

        List<Message> messages = awaitSqsMessages(2);
        assertThat(messages).hasSize(2);
        for (Message message : messages) {
            JsonNode payload = objectMapper.readTree(
                objectMapper.readTree(message.body()).get("Message").asText());
            assertThat(payload.get("data").get("exchangedDocument").get("identifier").asText())
                .isEqualTo(referenceNumber);
        }
    }

    @Test
    void replay_shouldWriteAuditRecord() {
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
        assertThat(audit.getNumberOfEvents()).isEqualTo(1);
        assertThat(audit.getTraceId()).isEqualTo("trace-audit");
        assertThat(audit.getUserId()).isEqualTo("user-audit-001");
        assertThat(audit.getTimestamp()).isNotNull();
    }

    @Test
    void replay_shouldReturn404_whenNoOutboxEventsExist() {
        // Given — notification exists but was never submitted (no outbox events)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(minimalNotificationDto()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody().getReferenceNumber();

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
