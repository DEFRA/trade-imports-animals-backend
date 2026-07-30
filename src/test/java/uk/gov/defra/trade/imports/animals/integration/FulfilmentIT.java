package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.fulfilment.Fulfilment;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentController;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentDto;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentPageResponse;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentRepository;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentStatus;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Operator;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

class FulfilmentIT extends IntegrationBase {

    private static final String FULFILMENT_ENDPOINT = "/fulfilments";
    private static final String REF_FORMAT_REGEX = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;
    private static final String LOCATION_FORMAT_REGEX =
        "http://localhost:8085/fulfilments/GBN-AG-\\d{2}-[0-9A-HJ-KM-NP-TV-Z]{6}";
    private static final String DIRECT_PUT_REF = "GBN-AG-26-ABC123";
    private static final String OTHER_REF = "GBN-AG-26-ABC124";
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";
    private static final String SCALAR_OBLIGATION_ID =
        "d34e5f6a-7b8c-4d9e-8f01-2a3b4c5d6e7f";
    private static final String GROUPED_OBLIGATION_ID =
        "a12e5f6a-7b8c-4d9e-8f01-2a3b4c5d6e70";

    @Autowired
    private FulfilmentRepository fulfilmentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        fulfilmentRepository.deleteAll();
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void post_shouldMintReferenceAndCreateEmptyDraftFulfilment() {
        Fulfilment created = createFulfilment();

        assertThat(created.getId()).matches(REF_FORMAT_REGEX);
        assertThat(created.getFulfilment()).isEmpty();
        assertThat(created.getSubmittedFulfilment()).isNull();
        assertThat(created.getStatus()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getSubmittedAt()).isNull();

        Fulfilment persisted = fulfilmentRepository.findById(created.getId()).orElseThrow();
        assertThat(persisted.getId()).isEqualTo(created.getId());
        assertThat(persisted.getFulfilment()).isEmpty();
        assertThat(persisted.getSubmittedFulfilment()).isNull();
        assertThat(persisted.getStatus()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(persisted.getCreatedAt()).isEqualTo(created.getCreatedAt().withNano(
            created.getCreatedAt().getNano() / 1_000_000 * 1_000_000));
        assertThat(persisted.getSubmittedAt()).isNull();

        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("DRAFT");
    }

    @Test
    void put_shouldCreateFulfilmentForClientKnownId() {
        FulfilmentDto dto = dto(DIRECT_PUT_REF, scalarFulfilment("internalMarket"));

        Fulfilment created = webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", DIRECT_PUT_REF)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueEquals(
                "Location", "http://localhost:8085/fulfilments/" + DIRECT_PUT_REF)
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getId()).isEqualTo(DIRECT_PUT_REF);
        assertThat(created.getFulfilment()).isEqualTo(dto.getFulfilment());
        assertThat(created.getStatus()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(fulfilmentRepository.count()).isEqualTo(1);
    }

    @Test
    void put_shouldWholeReplaceAndAllowIdempotentRetry() {
        Fulfilment created = createFulfilment();
        List<Document> firstSnapshot = List.of(
            new Document("obligationId", SCALAR_OBLIGATION_ID)
                .append("value", "internalMarket"),
            new Document("obligationId", OTHER_REF)
                .append("value", "historic"));
        replace(created.getId(), dto(created.getId(), firstSnapshot));

        FulfilmentDto replacement = dto(
            created.getId(), scalarFulfilment("transit"));
        Fulfilment replaced = replace(created.getId(), replacement);
        Fulfilment retried = replace(created.getId(), replacement);

        assertThat(replaced.getFulfilment()).isEqualTo(replacement.getFulfilment());
        assertThat(retried).isEqualTo(replaced);
        assertThat(retried.getFulfilment()).noneMatch(
            entry -> entry.containsValue("historic"));
        assertThat(fulfilmentRepository.count()).isEqualTo(1);
    }

    @Test
    void put_shouldReturn400_whenPathAndBodyIdsDiffer() {
        createFulfilmentWithId(DIRECT_PUT_REF);

        webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", DIRECT_PUT_REF)
            .bodyValue(dto(OTHER_REF, scalarFulfilment("transit")))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("must match"));
    }

    @Test
    void get_shouldReturnStoredFulfilment() {
        Fulfilment created = createFulfilment();
        FulfilmentDto dto = dto(created.getId(), scalarFulfilment("internalMarket"));
        replace(created.getId(), dto);

        Fulfilment found = webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getFulfilment()).isEqualTo(dto.getFulfilment());
    }

    @Test
    void get_shouldReturn404_whenFulfilmentDoesNotExist() {
        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void submit_shouldSetSubmittedAtAndBlockFurtherWrites() {
        Fulfilment created = createFulfilment();
        FulfilmentDto beforeSubmit = dto(
            created.getId(), scalarFulfilment("internalMarket"));
        replace(created.getId(), beforeSubmit);

        Fulfilment submitted = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(submitted).isNotNull();
        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(submitted.getSubmittedAt()).isNotNull();

        webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .bodyValue(dto(created.getId(), scalarFulfilment("transit")))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("writes blocked"));

        assertThat(fulfilmentRepository.findById(created.getId()).orElseThrow().getFulfilment())
            .isEqualTo(beforeSubmit.getFulfilment());

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot submit fulfilment with status: SUBMITTED"));
    }

    @Test
    void submit_shouldCascadeNotificationAndWriteSubmittedOutboxEvent() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();

        // When
        Fulfilment submitted = submitFulfilment(created.getId(), "trace-submit-cascade");

        // Then
        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        List<OutboxEvent> events = outboxEvents(created.getId());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType())
            .isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.value());
        assertThat(events.getFirst().getMetadata().getCorrelationId())
            .isEqualTo("trace-submit-cascade");
    }

    @Test
    void amend_shouldCascadeNotificationAndWriteAmendedOutboxEvent() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();
        submitFulfilment(created.getId(), "trace-submit-before-amend");

        // When
        Fulfilment amended = amendFulfilment(created.getId(), "trace-amend-cascade");

        // Then
        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.AMEND);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.AMEND);
        List<OutboxEvent> events = outboxEvents(created.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getEventType())
            .isEqualTo(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED.value());
        assertThat(events.get(1).getMetadata().getCorrelationId())
            .isEqualTo("trace-amend-cascade");
    }

    @Test
    void cancelAmend_shouldCascadeNotificationWithoutWritingNewOutboxEvent() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();
        submitFulfilment(created.getId(), "trace-submit-before-cancel");
        amendFulfilment(created.getId(), "trace-amend-before-cancel");
        long eventsBeforeCancel = outboxEventRepository.count();

        // When
        Fulfilment restored = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/cancel-amend", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        // Then
        assertThat(restored).isNotNull();
        assertThat(restored.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(outboxEventRepository.count()).isEqualTo(eventsBeforeCancel);
    }

    @Test
    void softDelete_shouldCascadeNotification() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();

        // When
        Fulfilment deleted = softDelete(created.getId());

        // Then
        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.DELETED);
        assertThat(outboxEvents(created.getId())).isEmpty();
    }

    @Test
    void fullRoundTrip_shouldCascadeAndEmitSecondSubmittedEvent() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();

        // When
        submitFulfilment(created.getId(), "trace-first-submit");
        amendFulfilment(created.getId(), "trace-round-trip-amend");
        Fulfilment resubmitted = submitFulfilment(
            created.getId(), "trace-second-submit");

        // Then
        assertThat(resubmitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        List<OutboxEvent> events = outboxEvents(created.getId());
        assertThat(events).extracting(OutboxEvent::getEventType)
            .containsExactly(
                OutboxEventType.NOTIFICATION_SUBMITTED.value(),
                OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED.value(),
                OutboxEventType.NOTIFICATION_SUBMITTED.value());
        assertThat(events.getLast().getMetadata().getCorrelationId())
            .isEqualTo("trace-second-submit");
    }

    @Test
    void submit_shouldSucceedWithoutNotificationProjectionOrOutboxEvent() {
        // Given
        Fulfilment created = createFulfilment();

        // When
        Fulfilment submitted = submitFulfilment(created.getId(), "trace-no-projection");

        // Then
        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(fulfilmentRepository.findById(created.getId()).orElseThrow().getStatus())
            .isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(notificationRepository.findByReferenceNumber(created.getId())).isEmpty();
        assertThat(outboxEvents(created.getId())).isEmpty();
    }

    @Test
    void submit_shouldRollbackCanonicalWhenNotificationStateIsUnexpected() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();
        webClient("NoAuth")
            .post().uri("/notifications/{id}/submit", created.getId())
            .header(NotificationController.HEADER_TRACE_ID, "trace-direct-submit")
            .exchange()
            .expectStatus().isOk();

        // When
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .header(NotificationController.HEADER_TRACE_ID, "trace-rejected-cascade")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot submit notification with status: SUBMITTED"));

        // Then
        assertThat(fulfilmentRepository.findById(created.getId()).orElseThrow().getStatus())
            .isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(outboxEvents(created.getId())).hasSize(1);
    }

    @Test
    void softDelete_shouldTolerateAlreadyDeletedNotificationProjection() {
        // Given
        Fulfilment created = createFulfilmentWithNotificationProjection();
        webClient("NoAuth")
            .post().uri("/notifications/{id}/soft-delete", created.getId())
            .exchange()
            .expectStatus().isOk();

        // When
        Fulfilment deleted = softDelete(created.getId());

        // Then
        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        assertThat(fulfilmentRepository.findById(created.getId()).orElseThrow().getStatus())
            .isEqualTo(FulfilmentStatus.DELETED);
        assertThat(notificationRepository.findByReferenceNumber(created.getId()).orElseThrow()
            .getStatus()).isEqualTo(NotificationStatus.DELETED);
        assertThat(outboxEvents(created.getId())).isEmpty();
    }

    @Test
    void amend_shouldSetAmendStatusAndAllowWritesAndResubmission() {
        Fulfilment created = createFulfilment();
        List<Document> submittedContent = scalarFulfilment("internalMarket");
        replace(created.getId(), dto(created.getId(), submittedContent));
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk();

        Fulfilment amended = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(amended).isNotNull();
        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.AMEND);
        assertThat(amended.getSubmittedFulfilment()).isEqualTo(submittedContent);
        assertThat(amended.getSubmittedFulfilment())
            .isNotSameAs(amended.getFulfilment());
        assertThat(amended.getSubmittedAt()).isNull();

        Fulfilment replaced = replace(
            created.getId(), dto(created.getId(), scalarFulfilment("transit")));
        assertThat(replaced.getFulfilment()).isEqualTo(scalarFulfilment("transit"));
        assertThat(replaced.getSubmittedFulfilment()).isEqualTo(submittedContent);

        Fulfilment resubmitted = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(resubmitted).isNotNull();
        assertThat(resubmitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(resubmitted.getSubmittedFulfilment()).isNull();
        assertThat(resubmitted.getSubmittedAt()).isNotNull();
    }

    @Test
    void cancelAmend_shouldDiscardEditsAndRestoreSubmittedContent() {
        Fulfilment created = createFulfilment();
        List<Document> submittedContent = scalarFulfilment("internalMarket");
        replace(created.getId(), dto(created.getId(), submittedContent));
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", created.getId())
            .exchange()
            .expectStatus().isOk();

        List<Document> amendEdits = scalarFulfilment("transit");
        Fulfilment edited = replace(
            created.getId(), dto(created.getId(), amendEdits));

        assertThat(edited.getFulfilment()).isEqualTo(amendEdits);
        assertThat(edited.getSubmittedFulfilment()).isEqualTo(submittedContent);

        Fulfilment restored = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/cancel-amend", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(restored).isNotNull();
        assertThat(restored.getFulfilment()).isEqualTo(submittedContent);
        assertThat(restored.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(restored.getSubmittedFulfilment()).isNull();
        assertThat(restored.getSubmittedAt()).isNotNull();

        Fulfilment found = webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();

        assertThat(found).isNotNull();
        assertThat(found.getFulfilment()).isEqualTo(submittedContent);
        assertThat(found.getFulfilment()).isNotEqualTo(amendEdits);
        assertThat(found.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(found.getSubmittedFulfilment()).isNull();
    }

    @Test
    void cancelAmend_shouldReturn400_whenFulfilmentIsNotAmend() {
        Fulfilment created = createFulfilment();

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/cancel-amend", created.getId())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString(
                    "Cannot cancel amendment for fulfilment with status: DRAFT"));
    }

    @Test
    void cancelAmend_shouldReturn400_whenSubmittedSnapshotIsMissing() {
        Fulfilment created = createFulfilment();
        Fulfilment withoutSnapshot =
            fulfilmentRepository.findById(created.getId()).orElseThrow();
        withoutSnapshot.setStatus(FulfilmentStatus.AMEND);
        fulfilmentRepository.save(withoutSnapshot);

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/cancel-amend", created.getId())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("no submitted snapshot"));
    }

    @Test
    void amend_shouldReturn400_whenFulfilmentIsDraft() {
        Fulfilment created = createFulfilment();

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", created.getId())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot amend fulfilment with status: DRAFT"));
    }

    @Test
    void deletedFulfilment_shouldRejectSubmitAndReplace() {
        Fulfilment created = createFulfilment();
        Fulfilment deleted = fulfilmentRepository.findById(created.getId()).orElseThrow();
        deleted.setStatus(FulfilmentStatus.DELETED);
        fulfilmentRepository.save(deleted);

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot submit fulfilment with status: DELETED"));

        webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .bodyValue(dto(created.getId(), scalarFulfilment("transit")))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("writes blocked"));

        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("DELETED");

        assertThat(fulfilmentRepository.findById(created.getId()).orElseThrow().getFulfilment())
            .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FulfilmentStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void copy_shouldPersistNewDraftFromCopyableStatus(FulfilmentStatus sourceStatus) {
        List<Document> sourceContent = scalarFulfilment(sourceStatus.name());
        Fulfilment source = stored(
            DIRECT_PUT_REF,
            sourceStatus,
            LocalDateTime.of(2026, 7, 24, 10, 0),
            sourceStatus == FulfilmentStatus.SUBMITTED
                ? LocalDateTime.of(2026, 7, 24, 11, 0)
                : null);
        source.setFulfilment(sourceContent);
        fulfilmentRepository.insert(source);

        Fulfilment copy = copyFulfilment(source.getId(), "copy-" + sourceStatus);

        assertThat(copy).isNotNull();
        assertThat(copy.getId()).matches(REF_FORMAT_REGEX).isNotEqualTo(source.getId());
        assertThat(copy.getFulfilment())
            .isEqualTo(sourceContent)
            .isNotSameAs(sourceContent);
        assertThat(copy.getStatus()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(copy.getCreatedAt()).isNotNull();
        assertThat(copy.getSubmittedAt()).isNull();
        assertThat(copy.getSubmittedFulfilment()).isNull();
        assertThat(copy.getCopyIdempotencyKey()).isEqualTo("copy-" + sourceStatus);

        Fulfilment persisted = fulfilmentRepository.findById(copy.getId()).orElseThrow();
        assertThat(persisted.getFulfilment()).isEqualTo(sourceContent);
        assertThat(persisted.getStatus()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(persisted.getSubmittedAt()).isNull();
        assertThat(persisted.getSubmittedFulfilment()).isNull();
        assertThat(persisted.getCopyIdempotencyKey()).isEqualTo("copy-" + sourceStatus);
        assertThat(fulfilmentRepository.count()).isEqualTo(2);
    }

    @Test
    void copy_shouldDeduplicateSameKeyAndCreateForDifferentKey() {
        Fulfilment source = createFulfilment();
        replace(source.getId(), dto(source.getId(), scalarFulfilment("internalMarket")));

        Fulfilment first = copyFulfilment(source.getId(), "same-key");
        Fulfilment retry = copyFulfilment(source.getId(), "same-key");
        Fulfilment differentKey = copyFulfilment(source.getId(), "different-key");

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(retry.getFulfilment()).isEqualTo(first.getFulfilment());
        assertThat(retry.getCopyIdempotencyKey()).isEqualTo(first.getCopyIdempotencyKey());
        assertThat(differentKey.getId()).isNotEqualTo(first.getId());
        assertThat(fulfilmentRepository.findByCopyIdempotencyKey("same-key"))
            .map(Fulfilment::getId)
            .contains(first.getId());
        assertThat(fulfilmentRepository.count()).isEqualTo(3);
    }

    @Test
    void copy_shouldReturn400ForDeletedSource() {
        Fulfilment source = createFulfilment();
        source.setStatus(FulfilmentStatus.DELETED);
        fulfilmentRepository.save(source);

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/copy", source.getId())
            .header(FulfilmentController.IDEMPOTENCY_KEY, "deleted-copy")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot copy fulfilment with status: DELETED"));

        assertThat(fulfilmentRepository.count()).isEqualTo(1);
    }

    @Test
    void copy_shouldReturn400ForMissingIdempotencyKey() {
        Fulfilment source = createFulfilment();

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/copy", source.getId())
            .exchange()
            .expectStatus().isBadRequest();

        assertThat(fulfilmentRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = FulfilmentStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void softDelete_shouldPersistDeletedAndRemainIdempotent(FulfilmentStatus sourceStatus) {
        Fulfilment source = stored(
            DIRECT_PUT_REF,
            sourceStatus,
            LocalDateTime.of(2026, 7, 24, 10, 0),
            null);
        fulfilmentRepository.insert(source);

        Fulfilment deleted = softDelete(source.getId());
        Fulfilment retried = softDelete(source.getId());

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        assertThat(retried).isEqualTo(deleted);
        assertThat(fulfilmentRepository.findById(source.getId()).orElseThrow().getStatus())
            .isEqualTo(FulfilmentStatus.DELETED);
        assertThat(fulfilmentRepository.count()).isEqualTo(1);

        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.totalElements").isEqualTo(0)
            .jsonPath("$.items.length()").isEqualTo(0);
    }

    @Test
    void putAndGet_shouldRoundTripOpaqueScalarAndCompositeRecordsWithoutInterpretation() {
        Fulfilment created = createFulfilment();
        List<Document> opaqueFulfilment = List.of(
            new Document("obligationId", SCALAR_OBLIGATION_ID)
                .append("value", "internalMarket"),
            new Document("obligationId", GROUPED_OBLIGATION_ID)
                .append("records", List.of(
                    new Document("fulfilmentId", "line0/unit1")
                        .append("value", new Document("uploadId", "upload-123")
                            .append("filename", "health-certificate.pdf")),
                    new Document("fulfilmentId", "line2/unit0")
                        .append("value", List.of("FR", "BE")))));
        JsonNode expected = objectMapper.valueToTree(opaqueFulfilment);

        replace(created.getId(), dto(created.getId(), opaqueFulfilment));

        JsonNode response = webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(JsonNode.class)
            .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.get("fulfilment")).isEqualTo(expected);
        assertThat(response.get("fulfilment").toString()).isEqualTo(expected.toString());

        JsonNode persisted = objectMapper.valueToTree(
            fulfilmentRepository.findById(created.getId()).orElseThrow().getFulfilment());
        assertThat(persisted).isEqualTo(expected);
        assertThat(persisted.toString()).isEqualTo(expected.toString());
    }

    @Test
    void list_shouldEnrichFromNotificationButKeepCanonicalFulfilmentState() {
        Fulfilment created = createFulfilment();
        replace(created.getId(), dto(created.getId(), scalarFulfilment("internalMarket")));
        Fulfilment submitted = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
        assertThat(submitted).isNotNull();

        Commodity commodity = Commodity.builder().name("Live animals").build();
        LocalDate arrivalDate = LocalDate.of(2026, 8, 12);
        Notification notificationProjection = putNotificationProjection(
            notificationDto(
                created.getId(),
                commodity,
                "FR",
                arrivalDate,
                "Example consignor",
                "Example consignee"));
        assertThat(notificationProjection).isNotNull();
        assertThat(notificationProjection.getStatus()).isEqualTo(NotificationStatus.DRAFT);

        Fulfilment withoutNotification = createFulfilmentWithId(OTHER_REF);
        Fulfilment deleted = stored(
            "GBN-AG-26-ABC125",
            FulfilmentStatus.DELETED,
            LocalDateTime.of(2026, 7, 24, 12, 0),
            null);
        fulfilmentRepository.insert(deleted);

        FulfilmentPageResponse page = listFulfilments(1, "arrivalDate,desc");

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(FulfilmentPageResponse.Item::id)
            .containsExactly(created.getId(), withoutNotification.getId());
        Fulfilment persistedCanonical =
            fulfilmentRepository.findById(created.getId()).orElseThrow();
        FulfilmentPageResponse.Item enriched = page.items().getFirst();
        assertThat(enriched.status()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(enriched.createdAt()).isEqualTo(persistedCanonical.getCreatedAt());
        assertThat(enriched.submittedAt()).isEqualTo(persistedCanonical.getSubmittedAt());
        assertThat(enriched.reference()).isEqualTo(created.getId());
        assertThat(enriched.commodityDisplay()).isEqualTo(commodity);
        assertThat(enriched.originCountryCode()).isEqualTo("FR");
        assertThat(enriched.arrivalDate()).isEqualTo(arrivalDate);
        assertThat(enriched.consignorName()).isEqualTo("Example consignor");
        assertThat(enriched.consigneeName()).isEqualTo("Example consignee");

        FulfilmentPageResponse.Item blank = page.items().get(1);
        assertThat(blank.status()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(blank.createdAt())
            .isEqualTo(fulfilmentRepository.findById(OTHER_REF).orElseThrow().getCreatedAt());
        assertThat(blank.submittedAt()).isNull();
        assertThat(blank.reference()).isEqualTo(OTHER_REF);
        assertThat(blank.commodityDisplay()).isNull();
        assertThat(blank.originCountryCode()).isNull();
        assertThat(blank.arrivalDate()).isNull();
        assertThat(blank.consignorName()).isNull();
        assertThat(blank.consigneeName()).isNull();
    }

    @Test
    void list_shouldSortByJoinedArrivalDateAndFulfilmentCreatedAtAndPage() {
        LocalDateTime createdBase = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDate arrivalBase = LocalDate.of(2026, 8, 1);
        List<Fulfilment> fulfilments = new ArrayList<>();
        List<Notification> notifications = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            String id = "GBN-AG-26-P" + "%05d".formatted(index);
            fulfilments.add(stored(
                id,
                FulfilmentStatus.DRAFT,
                createdBase.plusHours(index),
                null));
            notifications.add(Notification.builder()
                .referenceNumber(id)
                .status(NotificationStatus.DRAFT)
                .transport(Transport.builder()
                    .arrivalDate(arrivalBase.plusDays(index))
                    .build())
                .build());
        }
        fulfilmentRepository.insert(fulfilments);
        notificationRepository.insert(notifications);

        FulfilmentPageResponse defaultFirstPage =
            listFulfilmentsWithoutSort(1);
        FulfilmentPageResponse defaultSecondPage =
            listFulfilmentsWithoutSort(2);
        FulfilmentPageResponse invalidSortPage =
            listFulfilments(1, "submittedAt,asc");
        FulfilmentPageResponse createdAscendingPage =
            listFulfilments(1, "createdAt,asc");

        assertThat(defaultFirstPage.page()).isEqualTo(1);
        assertThat(defaultFirstPage.size()).isEqualTo(20);
        assertThat(defaultFirstPage.totalElements()).isEqualTo(21);
        assertThat(defaultFirstPage.totalPages()).isEqualTo(2);
        assertThat(defaultFirstPage.items()).hasSize(20);
        assertThat(defaultFirstPage.items().getFirst().id()).isEqualTo("GBN-AG-26-P00020");
        assertThat(defaultFirstPage.items().getFirst().arrivalDate())
            .isEqualTo(arrivalBase.plusDays(20));

        assertThat(defaultSecondPage.page()).isEqualTo(2);
        assertThat(defaultSecondPage.size()).isEqualTo(20);
        assertThat(defaultSecondPage.totalElements()).isEqualTo(21);
        assertThat(defaultSecondPage.totalPages()).isEqualTo(2);
        assertThat(defaultSecondPage.items()).hasSize(1);
        assertThat(defaultSecondPage.items().getFirst().id()).isEqualTo("GBN-AG-26-P00000");

        assertThat(invalidSortPage.items().getFirst().id()).isEqualTo("GBN-AG-26-P00020");
        assertThat(createdAscendingPage.items().getFirst().id())
            .isEqualTo("GBN-AG-26-P00000");
        assertThat(createdAscendingPage.items().getFirst().createdAt())
            .isEqualTo(createdBase);
    }

    @Test
    void list_shouldNormaliseInvalidPageAndSort() {
        LocalDateTime base = LocalDateTime.of(2026, 7, 24, 10, 0);
        fulfilmentRepository.insert(stored(
            DIRECT_PUT_REF, base, base.plusHours(3)));
        fulfilmentRepository.insert(stored(
            OTHER_REF, base.plusHours(1), base.plusHours(2)));
        fulfilmentRepository.insert(stored(
            "GBN-AG-26-ABC127",
            FulfilmentStatus.DELETED,
            base.plusHours(4),
            null));

        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "?page=0&sort=not-a-sort")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.page").isEqualTo(1)
            .jsonPath("$.size").isEqualTo(20)
            .jsonPath("$.totalElements").isEqualTo(2)
            .jsonPath("$.totalPages").isEqualTo(1)
            .jsonPath("$.items.length()").isEqualTo(2)
            .jsonPath("$.items[0].id").isEqualTo(DIRECT_PUT_REF)
            .jsonPath("$.items[0].status").isEqualTo("SUBMITTED")
            .jsonPath("$.items[0].createdAt").exists()
            .jsonPath("$.items[0].submittedAt").exists()
            .jsonPath("$.items[0].reference").isEqualTo(DIRECT_PUT_REF)
            .jsonPath("$.items[0].commodityDisplay").isEmpty()
            .jsonPath("$.items[0].originCountryCode").isEmpty()
            .jsonPath("$.items[0].arrivalDate").isEmpty()
            .jsonPath("$.items[0].consignorName").isEmpty()
            .jsonPath("$.items[0].consigneeName").isEmpty()
            .jsonPath("$.items[0].copyIdempotencyKey").doesNotExist()
            .jsonPath("$.items[0].fulfilment").doesNotExist()
            .jsonPath("$.items[0].submittedFulfilment").doesNotExist()
            .jsonPath("$.items[1].id").isEqualTo(OTHER_REF);
    }

    private Fulfilment createFulfilment() {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", LOCATION_FORMAT_REGEX)
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment createFulfilmentWithNotificationProjection() {
        Fulfilment created = createFulfilment();
        putNotificationProjection(
            notificationDto(
                created.getId(),
                Commodity.builder().name("Live animals").build(),
                "GB",
                LocalDate.of(2026, 8, 12),
                "Example consignor",
                "Example consignee"));
        return created;
    }

    private Fulfilment stored(
        String id, LocalDateTime createdAt, LocalDateTime submittedAt) {
        return stored(id, FulfilmentStatus.SUBMITTED, createdAt, submittedAt);
    }

    private Fulfilment stored(
        String id,
        FulfilmentStatus status,
        LocalDateTime createdAt,
        LocalDateTime submittedAt) {
        return Fulfilment.builder()
            .id(id)
            .fulfilment(List.of(new Document("sensitive", "body")))
            .status(status)
            .createdAt(createdAt)
            .submittedAt(submittedAt)
            .build();
    }

    private Fulfilment createFulfilmentWithId(String id) {
        return webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", id)
            .bodyValue(dto(id, List.of()))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment replace(String id, FulfilmentDto dto) {
        return webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", id)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment copyFulfilment(String id, String idempotencyKey) {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/copy", id)
            .header(FulfilmentController.IDEMPOTENCY_KEY, idempotencyKey)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", LOCATION_FORMAT_REGEX)
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment softDelete(String id) {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/soft-delete", id)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment submitFulfilment(String id, String traceId) {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", id)
            .header(NotificationController.HEADER_TRACE_ID, traceId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment amendFulfilment(String id, String traceId) {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", id)
            .header(NotificationController.HEADER_TRACE_ID, traceId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private List<OutboxEvent> outboxEvents(String referenceNumber) {
        return outboxEventRepository.findAllByAggregateIdOrderByAggregateVersionAsc(
            OutboxService.buildAggregateId(referenceNumber));
    }

    private Notification putNotificationProjection(NotificationDto dto) {
        return webClient("NoAuth")
            .put().uri("/notifications/{id}", dto.getReferenceNumber())
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();
    }

    private NotificationDto notificationDto(
        String reference,
        Commodity commodity,
        String originCountryCode,
        LocalDate arrivalDate,
        String consignorName,
        String consigneeName) {
        return NotificationDto.builder()
            .referenceNumber(reference)
            .commodity(commodity)
            .origin(Origin.builder().countryCode(originCountryCode).build())
            .transport(Transport.builder().arrivalDate(arrivalDate).build())
            .consignor(Operator.builder().name(consignorName).build())
            .consignee(Operator.builder().name(consigneeName).build())
            .build();
    }

    private FulfilmentPageResponse listFulfilments(int page, String sort) {
        return webClient("NoAuth")
            .get().uri(uriBuilder -> uriBuilder
                .path(FULFILMENT_ENDPOINT)
                .queryParam("page", page)
                .queryParam("sort", sort)
                .build())
            .exchange()
            .expectStatus().isOk()
            .expectBody(FulfilmentPageResponse.class)
            .returnResult().getResponseBody();
    }

    private FulfilmentPageResponse listFulfilmentsWithoutSort(int page) {
        return webClient("NoAuth")
            .get().uri(uriBuilder -> uriBuilder
                .path(FULFILMENT_ENDPOINT)
                .queryParam("page", page)
                .build())
            .exchange()
            .expectStatus().isOk()
            .expectBody(FulfilmentPageResponse.class)
            .returnResult().getResponseBody();
    }

    private FulfilmentDto dto(String id, List<Document> fulfilment) {
        return FulfilmentDto.builder()
            .id(id)
            .fulfilment(fulfilment)
            .build();
    }

    private List<Document> scalarFulfilment(String value) {
        return List.of(
            new Document("obligationId", SCALAR_OBLIGATION_ID)
                .append("value", value));
    }
}
