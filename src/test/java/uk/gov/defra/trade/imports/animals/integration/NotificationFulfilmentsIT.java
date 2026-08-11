package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
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
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsRepository;
import uk.gov.defra.trade.imports.animals.notificationfulfilments.NotificationFulfilmentsStatus;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
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
            LocalDateTime.of(2026, Month.JULY, 24, 10, 0),
            sourceStatus == NotificationFulfilmentsStatus.SUBMITTED
                ? LocalDateTime.of(2026, Month.JULY, 24, 11, 0)
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
        assertThat(copy.getIdempotencyKey()).isEqualTo("copy-" + sourceStatus);

        NotificationFulfilments persisted = notificationFulfilmentsRepository.findById(copy.getId()).orElseThrow();
        assertThat(persisted.getFulfilments()).isEqualTo(sourceContent);
        assertThat(persisted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(persisted.getSubmittedAt()).isNull();
        assertThat(persisted.getSubmittedFulfilments()).isNull();
        assertThat(persisted.getIdempotencyKey()).isEqualTo("copy-" + sourceStatus);
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
        assertThat(retry.getIdempotencyKey()).isEqualTo(first.getIdempotencyKey());
        assertThat(differentKey.getId()).isNotEqualTo(first.getId());
        assertThat(notificationFulfilmentsRepository.findByIdempotencyKey("same-key"))
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
            LocalDateTime.of(2026, Month.JULY, 24, 10, 0),
            null);
        notificationFulfilmentsRepository.insert(source);

        NotificationFulfilments deleted = softDelete(source.getId());
        NotificationFulfilments retried = softDelete(source.getId());

        assertThat(deleted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DELETED);
        assertThat(retried).isEqualTo(deleted);
        assertThat(notificationFulfilmentsRepository.findById(source.getId()).orElseThrow().getStatus())
            .isEqualTo(NotificationFulfilmentsStatus.DELETED);
        assertThat(notificationFulfilmentsRepository.count()).isEqualTo(1);
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
        assertThat(response.get("fulfilments")).hasToString(expected.toString());

        JsonNode persisted = objectMapper.valueToTree(
            notificationFulfilmentsRepository.findById(created.getId()).orElseThrow().getFulfilments());
        assertThat(persisted).isEqualTo(expected);
        assertThat(persisted).hasToString(expected.toString());
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
