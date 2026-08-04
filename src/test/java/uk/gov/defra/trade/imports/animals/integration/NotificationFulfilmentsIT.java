package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilments;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsController;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsDto;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsPageResponse;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsRepository;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsStatus;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Operator;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;

class NotificationFulfilmentsIT extends IntegrationBase {

    private static final String FULFILMENT_ENDPOINT = "/notification-fulfilments";
    private static final String REF_FORMAT_REGEX = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;
    private static final String LOCATION_FORMAT_REGEX =
        "http://localhost:8085/notification-fulfilments/GBN-AG-\\d{2}-[0-9A-HJ-KM-NP-TV-Z]{6}";
    private static final String DIRECT_PUT_REF = "GBN-AG-26-ABC123";
    private static final String OTHER_REF = "GBN-AG-26-ABC124";
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";
    private static final String SCALAR_OBLIGATION_ID =
        "d34e5f6a-7b8c-4d9e-8f01-2a3b4c5d6e7f";
    private static final String GROUPED_OBLIGATION_ID =
        "a12e5f6a-7b8c-4d9e-8f01-2a3b4c5d6e70";

    @Autowired
    private NotificationFulfilmentsRepository notificationFulfilmentsRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        notificationFulfilmentsRepository.deleteAll();
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void post_shouldMintReferenceAndCreateEmptyDraftFulfilment() {
        NotificationFulfilments created = createFulfilment();

        assertThat(created.getId()).matches(REF_FORMAT_REGEX);
        assertThat(created.getFulfilments()).isEmpty();
        assertThat(created.getSubmittedFulfilments()).isNull();
        assertThat(created.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getSubmittedAt()).isNull();

        NotificationFulfilments persisted = notificationFulfilmentsRepository.findById(created.getId()).orElseThrow();
        assertThat(persisted.getId()).isEqualTo(created.getId());
        assertThat(persisted.getFulfilments()).isEmpty();
        assertThat(persisted.getSubmittedFulfilments()).isNull();
        assertThat(persisted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
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
        NotificationFulfilmentsDto dto = dto(DIRECT_PUT_REF, scalarFulfilment("internalMarket"));

        NotificationFulfilments created = webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", DIRECT_PUT_REF)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueEquals(
                "Location", "http://localhost:8085/notification-fulfilments/" + DIRECT_PUT_REF)
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getId()).isEqualTo(DIRECT_PUT_REF);
        assertThat(created.getFulfilments()).isEqualTo(dto.getFulfilments());
        assertThat(created.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(1);
    }

    @Test
    void put_shouldWholeReplaceAndAllowIdempotentRetry() {
        NotificationFulfilments created = createFulfilment();
        List<Document> firstSnapshot = List.of(
            new Document("obligationId", SCALAR_OBLIGATION_ID)
                .append("value", "internalMarket"),
            new Document("obligationId", OTHER_REF)
                .append("value", "historic"));
        replace(created.getId(), dto(created.getId(), firstSnapshot));

        NotificationFulfilmentsDto replacement = dto(
            created.getId(), scalarFulfilment("transit"));
        NotificationFulfilments replaced = replace(created.getId(), replacement);
        NotificationFulfilments retried = replace(created.getId(), replacement);

        assertThat(replaced.getFulfilments()).isEqualTo(replacement.getFulfilments());
        assertThat(retried).isEqualTo(replaced);
        assertThat(retried.getFulfilments()).noneMatch(
            entry -> entry.containsValue("historic"));
        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(1);
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
        NotificationFulfilments created = createFulfilment();
        NotificationFulfilmentsDto dto = dto(created.getId(), scalarFulfilment("internalMarket"));
        replace(created.getId(), dto);

        NotificationFulfilments found = webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getFulfilments()).isEqualTo(dto.getFulfilments());
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
        NotificationFulfilments created = createFulfilment();
        NotificationFulfilmentsDto beforeSubmit = dto(
            created.getId(), scalarFulfilment("internalMarket"));
        replace(created.getId(), beforeSubmit);

        NotificationFulfilments submitted = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(submitted).isNotNull();
        assertThat(submitted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(submitted.getSubmittedAt()).isNotNull();

        webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .bodyValue(dto(created.getId(), scalarFulfilment("transit")))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("writes blocked"));

        assertThat(notificationFulfilmentsRepository.findById(created.getId()).orElseThrow().getFulfilments())
            .isEqualTo(beforeSubmit.getFulfilments());

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot submit fulfilment with status: SUBMITTED"));
    }

    @Test
    void amend_shouldSetAmendStatusAndAllowWritesAndResubmission() {
        NotificationFulfilments created = createFulfilment();
        List<Document> submittedContent = scalarFulfilment("internalMarket");
        replace(created.getId(), dto(created.getId(), submittedContent));
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk();

        NotificationFulfilments amended = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(amended).isNotNull();
        assertThat(amended.getStatus()).isEqualTo(NotificationFulfilmentsStatus.AMEND);
        assertThat(amended.getSubmittedFulfilments()).isEqualTo(submittedContent);
        assertThat(amended.getSubmittedFulfilments())
            .isNotSameAs(amended.getFulfilments());
        assertThat(amended.getSubmittedAt()).isNull();

        NotificationFulfilments replaced = replace(
            created.getId(), dto(created.getId(), scalarFulfilment("transit")));
        assertThat(replaced.getFulfilments()).isEqualTo(scalarFulfilment("transit"));
        assertThat(replaced.getSubmittedFulfilments()).isEqualTo(submittedContent);

        NotificationFulfilments resubmitted = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(resubmitted).isNotNull();
        assertThat(resubmitted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(resubmitted.getSubmittedFulfilments()).isNull();
        assertThat(resubmitted.getSubmittedAt()).isNotNull();
    }

    @Test
    void cancelAmend_shouldDiscardEditsAndRestoreSubmittedContent() {
        NotificationFulfilments created = createFulfilment();
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
        NotificationFulfilments edited = replace(
            created.getId(), dto(created.getId(), amendEdits));

        assertThat(edited.getFulfilments()).isEqualTo(amendEdits);
        assertThat(edited.getSubmittedFulfilments()).isEqualTo(submittedContent);

        NotificationFulfilments restored = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/cancel-amend", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(restored).isNotNull();
        assertThat(restored.getFulfilments()).isEqualTo(submittedContent);
        assertThat(restored.getStatus()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(restored.getSubmittedFulfilments()).isNull();
        assertThat(restored.getSubmittedAt()).isNotNull();

        NotificationFulfilments found = webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();

        assertThat(found).isNotNull();
        assertThat(found.getFulfilments()).isEqualTo(submittedContent);
        assertThat(found.getFulfilments()).isNotEqualTo(amendEdits);
        assertThat(found.getStatus()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(found.getSubmittedFulfilments()).isNull();
    }

    @Test
    void cancelAmend_shouldReturn400_whenFulfilmentIsNotAmend() {
        NotificationFulfilments created = createFulfilment();

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
        NotificationFulfilments created = createFulfilment();
        NotificationFulfilments withoutSnapshot =
            notificationFulfilmentsRepository.findById(created.getId()).orElseThrow();
        withoutSnapshot.setStatus(NotificationFulfilmentsStatus.AMEND);
        notificationFulfilmentsRepository.save(withoutSnapshot);

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
        NotificationFulfilments created = createFulfilment();

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
        NotificationFulfilments created = createFulfilment();
        NotificationFulfilments deleted = notificationFulfilmentsRepository.findById(created.getId()).orElseThrow();
        deleted.setStatus(NotificationFulfilmentsStatus.DELETED);
        notificationFulfilmentsRepository.save(deleted);

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

        assertThat(notificationFulfilmentsRepository.findById(created.getId()).orElseThrow().getFulfilments())
            .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFulfilmentsStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void copy_shouldPersistNewDraftFromCopyableStatus(NotificationFulfilmentsStatus sourceStatus) {
        List<Document> sourceContent = scalarFulfilment(sourceStatus.name());
        NotificationFulfilments source = stored(
            DIRECT_PUT_REF,
            sourceStatus,
            LocalDateTime.of(2026, 7, 24, 10, 0),
            sourceStatus == NotificationFulfilmentsStatus.SUBMITTED
                ? LocalDateTime.of(2026, 7, 24, 11, 0)
                : null);
        source.setFulfilments(sourceContent);
        notificationFulfilmentsRepository.insert(source);

        NotificationFulfilments copy = copyFulfilment(source.getId(), "copy-" + sourceStatus);

        assertThat(copy).isNotNull();
        assertThat(copy.getId()).matches(REF_FORMAT_REGEX).isNotEqualTo(source.getId());
        assertThat(copy.getFulfilments())
            .isEqualTo(sourceContent)
            .isNotSameAs(sourceContent);
        assertThat(copy.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(copy.getCreatedAt()).isNotNull();
        assertThat(copy.getSubmittedAt()).isNull();
        assertThat(copy.getSubmittedFulfilments()).isNull();
        assertThat(copy.getCopyIdempotencyKey()).isEqualTo("copy-" + sourceStatus);

        NotificationFulfilments persisted = notificationFulfilmentsRepository.findById(copy.getId()).orElseThrow();
        assertThat(persisted.getFulfilments()).isEqualTo(sourceContent);
        assertThat(persisted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(persisted.getSubmittedAt()).isNull();
        assertThat(persisted.getSubmittedFulfilments()).isNull();
        assertThat(persisted.getCopyIdempotencyKey()).isEqualTo("copy-" + sourceStatus);
        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(2);
    }

    @Test
    void copy_shouldDeduplicateSameKeyAndCreateForDifferentKey() {
        NotificationFulfilments source = createFulfilment();
        replace(source.getId(), dto(source.getId(), scalarFulfilment("internalMarket")));

        NotificationFulfilments first = copyFulfilment(source.getId(), "same-key");
        NotificationFulfilments retry = copyFulfilment(source.getId(), "same-key");
        NotificationFulfilments differentKey = copyFulfilment(source.getId(), "different-key");

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(retry.getFulfilments()).isEqualTo(first.getFulfilments());
        assertThat(retry.getCopyIdempotencyKey()).isEqualTo(first.getCopyIdempotencyKey());
        assertThat(differentKey.getId()).isNotEqualTo(first.getId());
        assertThat(notificationFulfilmentsRepository.findByCopyIdempotencyKey("same-key"))
            .map(NotificationFulfilments::getId)
            .contains(first.getId());
        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(3);
    }

    @Test
    void copy_shouldReturn400ForDeletedSource() {
        NotificationFulfilments source = createFulfilment();
        source.setStatus(NotificationFulfilmentsStatus.DELETED);
        notificationFulfilmentsRepository.save(source);

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/copy", source.getId())
            .header(NotificationFulfilmentsController.IDEMPOTENCY_KEY, "deleted-copy")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(
                Matchers.containsString("Cannot copy fulfilment with status: DELETED"));

        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(1);
    }

    @Test
    void copy_shouldReturn400ForMissingIdempotencyKey() {
        NotificationFulfilments source = createFulfilment();

        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/copy", source.getId())
            .exchange()
            .expectStatus().isBadRequest();

        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFulfilmentsStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void softDelete_shouldPersistDeletedAndRemainIdempotent(NotificationFulfilmentsStatus sourceStatus) {
        NotificationFulfilments source = stored(
            DIRECT_PUT_REF,
            sourceStatus,
            LocalDateTime.of(2026, 7, 24, 10, 0),
            null);
        notificationFulfilmentsRepository.insert(source);

        NotificationFulfilments deleted = softDelete(source.getId());
        NotificationFulfilments retried = softDelete(source.getId());

        assertThat(deleted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DELETED);
        assertThat(retried).isEqualTo(deleted);
        assertThat(notificationFulfilmentsRepository.findById(source.getId()).orElseThrow().getStatus())
            .isEqualTo(NotificationFulfilmentsStatus.DELETED);
        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(1);

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
        NotificationFulfilments created = createFulfilment();
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
        assertThat(response.get("fulfilments")).isEqualTo(expected);
        assertThat(response.get("fulfilments").toString()).isEqualTo(expected.toString());

        JsonNode persisted = objectMapper.valueToTree(
            notificationFulfilmentsRepository.findById(created.getId()).orElseThrow().getFulfilments());
        assertThat(persisted).isEqualTo(expected);
        assertThat(persisted.toString()).isEqualTo(expected.toString());
    }

    @Test
    void list_shouldEnrichFromNotificationWhenPresent() {
        NotificationFulfilments created = createFulfilment();
        replace(created.getId(), dto(created.getId(), scalarFulfilment("internalMarket")));
        NotificationFulfilments submitted = webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();
        assertThat(submitted).isNotNull();

        Commodity commodity = Commodity.builder().name("Live animals").build();
        LocalDate arrivalDate = LocalDate.of(2026, 8, 12);
        notificationRepository.save(
            Notification.builder()
                .referenceNumber(created.getId())
                .status(NotificationStatus.DRAFT)
                .commodity(commodity)
                .origin(Origin.builder().countryCode("FR").build())
                .transport(Transport.builder().arrivalDate(arrivalDate).build())
                .consignor(Operator.builder().name("Example consignor").build())
                .consignee(Operator.builder().name("Example consignee").build())
                .build());

        NotificationFulfilmentsPageResponse page = listFulfilments(1, "arrivalDate,desc");
        NotificationFulfilments persistedCanonical =
            notificationFulfilmentsRepository.findById(created.getId()).orElseThrow();
        NotificationFulfilmentsPageResponse.Item enriched = page.items().getFirst();

        assertThat(enriched.status()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(enriched.createdAt()).isEqualTo(persistedCanonical.getCreatedAt());
        assertThat(enriched.submittedAt()).isEqualTo(persistedCanonical.getSubmittedAt());
        assertThat(enriched.reference()).isEqualTo(created.getId());
        assertThat(enriched.commodityDisplay()).isEqualTo(commodity);
        assertThat(enriched.originCountryCode()).isEqualTo("FR");
        assertThat(enriched.arrivalDate()).isEqualTo(arrivalDate);
        assertThat(enriched.consignorName()).isEqualTo("Example consignor");
        assertThat(enriched.consigneeName()).isEqualTo("Example consignee");
    }

    @Test
    void list_shouldLeaveNotificationFieldsBlankWhenNoNotificationExists() {
        NotificationFulfilments created = createFulfilmentWithId(OTHER_REF);
        NotificationFulfilments deleted = stored(
            "GBN-AG-26-ABC125",
            NotificationFulfilmentsStatus.DELETED,
            LocalDateTime.of(2026, 7, 24, 12, 0),
            null);
        notificationFulfilmentsRepository.insert(deleted);

        NotificationFulfilmentsPageResponse page = listFulfilments(1, "arrivalDate,desc");

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).extracting(NotificationFulfilmentsPageResponse.Item::id)
            .containsExactly(created.getId());
        NotificationFulfilmentsPageResponse.Item blank = page.items().getFirst();
        assertThat(blank.status()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(blank.createdAt())
            .isEqualTo(notificationFulfilmentsRepository.findById(OTHER_REF).orElseThrow().getCreatedAt());
        assertThat(blank.submittedAt()).isNull();
        assertThat(blank.reference()).isEqualTo(OTHER_REF);
        assertThat(blank.commodityDisplay()).isNull();
        assertThat(blank.originCountryCode()).isNull();
        assertThat(blank.arrivalDate()).isNull();
        assertThat(blank.consignorName()).isNull();
        assertThat(blank.consigneeName()).isNull();
    }

    @Test
    void list_shouldReturnOnlyExactReferenceMatch() {
        createFulfilmentWithId(DIRECT_PUT_REF);
        createFulfilmentWithId(OTHER_REF);

        NotificationFulfilmentsPageResponse page =
            listFulfilments(1, null, DIRECT_PUT_REF);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).extracting(NotificationFulfilmentsPageResponse.Item::id)
            .containsExactly(DIRECT_PUT_REF);
    }

    @Test
    void list_shouldReturnEmptyPageWhenReferenceDoesNotMatch() {
        createFulfilmentWithId(DIRECT_PUT_REF);

        NotificationFulfilmentsPageResponse page =
            listFulfilments(1, null, "GBN-AG-26-ZZZZZZ");

        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void list_shouldPreserveSortWhenFilteringByReference() {
        LocalDateTime createdBase = LocalDateTime.of(2026, 7, 1, 10, 0);
        notificationFulfilmentsRepository.insert(stored(
            DIRECT_PUT_REF, NotificationFulfilmentsStatus.DRAFT, createdBase.plusHours(1), null));
        notificationFulfilmentsRepository.insert(stored(
            OTHER_REF, NotificationFulfilmentsStatus.DRAFT, createdBase, null));

        NotificationFulfilmentsPageResponse page =
            listFulfilments(1, "createdAt,asc", DIRECT_PUT_REF);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).extracting(NotificationFulfilmentsPageResponse.Item::id)
            .containsExactly(DIRECT_PUT_REF);
        assertThat(page.items().getFirst().createdAt())
            .isEqualTo(createdBase.plusHours(1));
    }

    @Test
    void list_shouldSortByJoinedArrivalDateAndFulfilmentCreatedAtAndPage() {
        LocalDateTime createdBase = LocalDateTime.of(2026, 7, 1, 10, 0);
        LocalDate arrivalBase = LocalDate.of(2026, 8, 1);
        List<NotificationFulfilments> fulfilments = new ArrayList<>();
        List<Notification> notifications = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            String id = "GBN-AG-26-P" + "%05d".formatted(index);
            fulfilments.add(stored(
                id,
                NotificationFulfilmentsStatus.DRAFT,
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
        notificationFulfilmentsRepository.insert(fulfilments);
        notificationRepository.insert(notifications);

        NotificationFulfilmentsPageResponse defaultFirstPage =
            listFulfilmentsWithoutSort(1);
        NotificationFulfilmentsPageResponse defaultSecondPage =
            listFulfilmentsWithoutSort(2);
        NotificationFulfilmentsPageResponse invalidSortPage =
            listFulfilments(1, "submittedAt,asc");
        NotificationFulfilmentsPageResponse createdAscendingPage =
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
        notificationFulfilmentsRepository.insert(stored(
            DIRECT_PUT_REF, base, base.plusHours(3)));
        notificationFulfilmentsRepository.insert(stored(
            OTHER_REF, base.plusHours(1), base.plusHours(2)));
        notificationFulfilmentsRepository.insert(stored(
            "GBN-AG-26-ABC127",
            NotificationFulfilmentsStatus.DELETED,
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
            .jsonPath("$.items[0].fulfilments").doesNotExist()
            .jsonPath("$.items[0].submittedFulfilments").doesNotExist()
            .jsonPath("$.items[1].id").isEqualTo(OTHER_REF);
    }

    private NotificationFulfilments createFulfilment() {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", LOCATION_FORMAT_REGEX)
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilments stored(
        String id, LocalDateTime createdAt, LocalDateTime submittedAt) {
        return stored(id, NotificationFulfilmentsStatus.SUBMITTED, createdAt, submittedAt);
    }

    private NotificationFulfilments stored(
        String id,
        NotificationFulfilmentsStatus status,
        LocalDateTime createdAt,
        LocalDateTime submittedAt) {
        return NotificationFulfilments.builder()
            .id(id)
            .fulfilments(List.of(new Document("sensitive", "body")))
            .status(status)
            .createdAt(createdAt)
            .submittedAt(submittedAt)
            .build();
    }

    private NotificationFulfilments createFulfilmentWithId(String id) {
        return webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", id)
            .bodyValue(dto(id, List.of()))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilments replace(String id, NotificationFulfilmentsDto dto) {
        return webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", id)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilments copyFulfilment(String id, String idempotencyKey) {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/copy", id)
            .header(NotificationFulfilmentsController.IDEMPOTENCY_KEY, idempotencyKey)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", LOCATION_FORMAT_REGEX)
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilments softDelete(String id) {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/soft-delete", id)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilments.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilmentsPageResponse listFulfilments(int page, String sort) {
        return listFulfilments(page, sort, null);
    }

    private NotificationFulfilmentsPageResponse listFulfilments(
        int page, String sort, String referenceNumber) {
        return webClient("NoAuth")
            .get().uri(uriBuilder -> uriBuilder
                .path(FULFILMENT_ENDPOINT)
                .queryParam("page", page)
                .queryParamIfPresent("sort", Optional.ofNullable(sort))
                .queryParamIfPresent(
                    "referenceNumber", Optional.ofNullable(referenceNumber))
                .build())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilmentsPageResponse.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilmentsPageResponse listFulfilmentsWithoutSort(int page) {
        return webClient("NoAuth")
            .get().uri(uriBuilder -> uriBuilder
                .path(FULFILMENT_ENDPOINT)
                .queryParam("page", page)
                .build())
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationFulfilmentsPageResponse.class)
            .returnResult().getResponseBody();
    }

    private NotificationFulfilmentsDto dto(String id, List<Document> fulfilment) {
        return NotificationFulfilmentsDto.builder()
            .id(id)
            .fulfilments(fulfilment)
            .build();
    }

    private List<Document> scalarFulfilment(String value) {
        return List.of(
            new Document("obligationId", SCALAR_OBLIGATION_ID)
                .append("value", value));
    }
}
