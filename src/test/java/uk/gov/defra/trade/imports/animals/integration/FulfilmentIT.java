package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.fulfilment.Fulfilment;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentDto;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentRepository;
import uk.gov.defra.trade.imports.animals.fulfilment.FulfilmentStatus;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

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
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        fulfilmentRepository.deleteAll();
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
        assertThat(created.getOwner()).isEqualTo(DEFAULT_OWNER);

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
    void cancelAmend_shouldReturn404_forDifferentOwner() {
        Fulfilment created = createFulfilment();
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", created.getId())
            .exchange()
            .expectStatus().isOk();
        Owner differentOwner = new Owner("different-user", "different-org");

        webClient("NoAuth", differentOwner)
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/cancel-amend", created.getId())
            .exchange()
            .expectStatus().isNotFound();
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
    void ownerRelevantOperations_shouldReturn404_forDifferentOwner() {
        Fulfilment created = createFulfilment();
        Owner differentOwner = new Owner("different-user", "different-org");

        webClient("NoAuth", differentOwner)
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .exchange()
            .expectStatus().isNotFound();

        webClient("NoAuth", differentOwner)
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", created.getId())
            .bodyValue(dto(created.getId(), scalarFulfilment("transit")))
            .exchange()
            .expectStatus().isNotFound();

        webClient("NoAuth", differentOwner)
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/submit", created.getId())
            .exchange()
            .expectStatus().isNotFound();

        webClient("NoAuth", differentOwner)
            .post().uri(FULFILMENT_ENDPOINT + "/{id}/amend", created.getId())
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void list_shouldScopeBeforePagingAndNormaliseInvalidPageAndSort() {
        Owner secondOwner = new Owner("second-user", "second-org");
        LocalDateTime base = LocalDateTime.of(2026, 7, 24, 10, 0);
        fulfilmentRepository.insert(stored(
            DIRECT_PUT_REF, DEFAULT_OWNER, base, base.plusHours(3)));
        fulfilmentRepository.insert(stored(
            OTHER_REF, DEFAULT_OWNER, base.plusHours(1), base.plusHours(2)));
        fulfilmentRepository.insert(stored(
            "GBN-AG-26-ABC125", secondOwner, base.plusHours(2), base.plusHours(1)));
        fulfilmentRepository.insert(stored(
            "GBN-AG-26-ABC126", null, base.plusHours(3), null));
        fulfilmentRepository.insert(stored(
            "GBN-AG-26-ABC127",
            DEFAULT_OWNER,
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
            .jsonPath("$.items[0].id").isEqualTo(OTHER_REF)
            .jsonPath("$.items[0].status").isEqualTo("SUBMITTED")
            .jsonPath("$.items[0].createdAt").exists()
            .jsonPath("$.items[0].submittedAt").exists()
            .jsonPath("$.items[0].fulfilment").doesNotExist()
            .jsonPath("$.items[0].submittedFulfilment").doesNotExist()
            .jsonPath("$.items[1].id").isEqualTo(DIRECT_PUT_REF);

        webClient("NoAuth", secondOwner)
            .get().uri(FULFILMENT_ENDPOINT + "?page=1&sort=submittedAt,asc")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.totalElements").isEqualTo(1)
            .jsonPath("$.items[0].id").isEqualTo("GBN-AG-26-ABC125");
    }

    @Test
    void legacyUnownedFulfilment_shouldBeHiddenAndReturn404() {
        fulfilmentRepository.insert(stored(
            DIRECT_PUT_REF, null, LocalDateTime.of(2026, 7, 24, 10, 0), null));

        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.totalElements").isEqualTo(0)
            .jsonPath("$.items.length()").isEqualTo(0);

        webClient("NoAuth")
            .get().uri(FULFILMENT_ENDPOINT + "/{id}", DIRECT_PUT_REF)
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void post_shouldPreserveEmptyOrganisationAsOwnerValue() {
        Owner ownerWithoutOrganisation = new Owner("stub-user", "");

        Fulfilment created = createFulfilment(ownerWithoutOrganisation);

        assertThat(created.getOwner()).isEqualTo(ownerWithoutOrganisation);
        assertThat(fulfilmentRepository.findById(created.getId()).orElseThrow().getOwner())
            .isEqualTo(ownerWithoutOrganisation);
    }

    private Fulfilment createFulfilment() {
        return createFulfilment(DEFAULT_OWNER);
    }

    private Fulfilment createFulfilment(Owner owner) {
        return webClient("NoAuth", owner)
            .post().uri(FULFILMENT_ENDPOINT)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", LOCATION_FORMAT_REGEX)
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
    }

    private Fulfilment stored(
        String id, Owner owner, LocalDateTime createdAt, LocalDateTime submittedAt) {
        return stored(id, owner, FulfilmentStatus.SUBMITTED, createdAt, submittedAt);
    }

    private Fulfilment stored(
        String id,
        Owner owner,
        FulfilmentStatus status,
        LocalDateTime createdAt,
        LocalDateTime submittedAt) {
        return Fulfilment.builder()
            .id(id)
            .owner(owner)
            .fulfilment(List.of(new Document("sensitive", "body")))
            .status(status)
            .createdAt(createdAt)
            .submittedAt(submittedAt)
            .build();
    }

    private void createFulfilmentWithId(String id) {
        webClient("NoAuth")
            .put().uri(FULFILMENT_ENDPOINT + "/{id}", id)
            .bodyValue(dto(id, List.of()))
            .exchange()
            .expectStatus().isCreated();
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
