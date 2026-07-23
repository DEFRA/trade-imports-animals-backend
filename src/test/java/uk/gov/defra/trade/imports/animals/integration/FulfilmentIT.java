package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void post_shouldMintReferenceAndCreateEmptyInProgressFulfilment() {
        Fulfilment created = createFulfilment();

        assertThat(created.getId()).matches(REF_FORMAT_REGEX);
        assertThat(created.getFulfilment()).isEmpty();
        assertThat(created.getStatus()).isEqualTo(FulfilmentStatus.IN_PROGRESS);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getSubmittedAt()).isNull();

        Fulfilment persisted = fulfilmentRepository.findById(created.getId()).orElseThrow();
        assertThat(persisted.getId()).isEqualTo(created.getId());
        assertThat(persisted.getFulfilment()).isEmpty();
        assertThat(persisted.getStatus()).isEqualTo(FulfilmentStatus.IN_PROGRESS);
        assertThat(persisted.getCreatedAt()).isEqualTo(created.getCreatedAt().withNano(
            created.getCreatedAt().getNano() / 1_000_000 * 1_000_000));
        assertThat(persisted.getSubmittedAt()).isNull();
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
        assertThat(created.getStatus()).isEqualTo(FulfilmentStatus.IN_PROGRESS);
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
    }

    @Test
    void amend_shouldReopenSubmittedFulfilmentAndAllowWrites() {
        Fulfilment created = createFulfilment();
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
        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.IN_PROGRESS);
        assertThat(amended.getSubmittedAt()).isNull();

        Fulfilment replaced = replace(
            created.getId(), dto(created.getId(), scalarFulfilment("transit")));
        assertThat(replaced.getFulfilment()).isEqualTo(scalarFulfilment("transit"));
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

    private Fulfilment createFulfilment() {
        return webClient("NoAuth")
            .post().uri(FULFILMENT_ENDPOINT)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches("Location", LOCATION_FORMAT_REGEX)
            .expectBody(Fulfilment.class)
            .returnResult().getResponseBody();
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
