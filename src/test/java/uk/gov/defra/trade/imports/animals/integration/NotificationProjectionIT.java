package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.proposednotification.ProposedNotification;
import uk.gov.defra.trade.imports.animals.proposednotification.ProposedNotificationRepository;

class NotificationProjectionIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String PROPOSED_NOTIFICATION_ENDPOINT = "/proposed-notifications";
    private static final String REF = "GBN-AG-26-ABC123";
    private static final String OTHER_REF = "GBN-AG-26-ABC124";
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProposedNotificationRepository proposedNotificationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        proposedNotificationRepository.deleteAll();
    }

    @Test
    void putNotification_shouldCreateCurrentProjectionForClientKnownId() {
        NotificationDto dto = currentNotification(REF, "PERMANENT", "GB");

        Notification created = webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueEquals(
                "Location", "http://localhost:8085/notifications/" + REF)
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getReferenceNumber()).isEqualTo(REF);
        assertThat(created.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(created.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(created.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(created.getCreated()).isNotNull();
        assertThat(created.getUpdated()).isNotNull();
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void putNotification_shouldWholeReplaceAndAllowIdempotentRetry() {
        putCurrentNotification(
            currentNotification(REF, "PERMANENT", "GB"), true);

        NotificationDto replacement = currentNotification(REF, "SHOW", "FR");
        replacement.setCphNumber("12/345/6789");

        Notification replaced = putCurrentNotification(replacement, false);
        Notification persistedAfterReplace =
            notificationRepository.findByReferenceNumber(REF).orElseThrow();
        Notification retried = putCurrentNotification(replacement, false);
        Notification persistedAfterRetry =
            notificationRepository.findByReferenceNumber(REF).orElseThrow();

        assertThat(replaced.getId()).isEqualTo(retried.getId());
        assertThat(retried.getReasonForImport()).isEqualTo("SHOW");
        assertThat(retried.getOrigin().getCountryCode()).isEqualTo("FR");
        assertThat(retried.getCphNumber()).isEqualTo("12/345/6789");
        assertThat(persistedAfterRetry).isEqualTo(persistedAfterReplace);
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void putNotification_shouldReturn400_whenPathAndBodyReferenceNumbersDiffer() {
        webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(currentNotification(OTHER_REF, "SHOW", "FR"))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("must match"));

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void putProposedNotification_shouldCreateOpaqueProjection() throws Exception {
        String body = fullFatBody(REF, "Breeding");

        byte[] response = webClient("NoAuth")
            .put().uri(PROPOSED_NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(body)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueEquals(
                "Location", "http://localhost:8085/proposed-notifications/" + REF)
            .expectBody()
            .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo(body);
        ProposedNotification persisted =
            proposedNotificationRepository.findById(REF).orElseThrow();
        JsonNode persistedBody = objectMapper.valueToTree(persisted.getBody());
        assertThat(persistedBody).isEqualTo(objectMapper.readTree(body));
        assertThat(proposedNotificationRepository.count()).isEqualTo(1);
    }

    @Test
    void putProposedNotification_shouldWholeReplaceAndAllowIdempotentRetry()
        throws Exception {
        putProposedNotification(fullFatBody(REF, "Breeding"), true);
        String replacement = fullFatBody(REF, "Sale");

        JsonNode replaced = putProposedNotification(replacement, false);
        JsonNode persistedAfterReplace = objectMapper.valueToTree(
            proposedNotificationRepository.findById(REF).orElseThrow().getBody());
        JsonNode retried = putProposedNotification(replacement, false);
        JsonNode persistedAfterRetry = objectMapper.valueToTree(
            proposedNotificationRepository.findById(REF).orElseThrow().getBody());

        assertThat(replaced).isEqualTo(objectMapper.readTree(replacement));
        assertThat(retried).isEqualTo(replaced);
        assertThat(persistedAfterRetry).isEqualTo(persistedAfterReplace);
        assertThat(proposedNotificationRepository.count()).isEqualTo(1);
    }

    @Test
    void putProposedNotification_shouldReturn400_whenReferencesDiffer() {
        webClient("NoAuth")
            .put().uri(PROPOSED_NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(fullFatBody(OTHER_REF, "Breeding"))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("must match"));

        assertThat(proposedNotificationRepository.count()).isZero();
    }

    @Test
    void getProposedNotification_shouldReturnByteFaithfulOpaqueBodyWithUnknownFields() {
        String body = fullFatBody(REF, "Breeding");
        putProposedNotification(body, true);

        byte[] response = webClient("NoAuth")
            .get().uri(PROPOSED_NOTIFICATION_ENDPOINT + "/{id}", REF)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.futureTopLevel.version").isEqualTo(2)
            .jsonPath("$.commodity.commodityComplement[0].futureComplementField")
            .isEqualTo("retained")
            .jsonPath("$.commodity.commodityComplement[0].species[0]"
                + ".animalIdentifiers[0].futureIdentifierField")
            .isEqualTo("retained")
            .jsonPath("$.documents[0].futureDocumentField.nested").isEqualTo(true)
            .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    void getProposedNotification_shouldReturn404_whenProjectionDoesNotExist() {
        webClient("NoAuth")
            .get().uri(PROPOSED_NOTIFICATION_ENDPOINT + "/{id}", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    private Notification putCurrentNotification(NotificationDto dto, boolean created) {
        var response = webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{id}", dto.getReferenceNumber())
            .bodyValue(dto)
            .exchange();
        if (created) {
            response.expectStatus().isCreated();
        } else {
            response.expectStatus().isOk();
        }
        return response.expectBody(Notification.class)
            .returnResult().getResponseBody();
    }

    private JsonNode putProposedNotification(String body, boolean created) {
        var response = webClient("NoAuth")
            .put().uri(PROPOSED_NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(body)
            .exchange();
        if (created) {
            response.expectStatus().isCreated();
        } else {
            response.expectStatus().isOk();
        }
        return response.expectBody(JsonNode.class)
            .returnResult().getResponseBody();
    }

    private NotificationDto currentNotification(
        String referenceNumber, String reasonForImport, String countryCode) {
        return NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .reasonForImport(reasonForImport)
            .origin(new Origin(countryCode, "false", "PROJECTION-REF"))
            .build();
    }

    private String fullFatBody(String referenceNumber, String purpose) {
        return "{\"referenceNumber\":\"" + referenceNumber
            + "\",\"origin\":{\"countryCode\":\"FR\",\"regionCode\":\"FR-75\"}"
            + ",\"purpose\":\"" + purpose
            + "\",\"transport\":{\"meansOfTransport\":\"ROAD_VEHICLE\""
            + ",\"transportIdentification\":\"FR-892-LK\""
            + ",\"transitedCountries\":[\"BE\",\"NL\"]}"
            + ",\"commodity\":{\"name\":\"Live animals\""
            + ",\"commodityComplement\":[{\"commodityCode\":\"0102\""
            + ",\"name\":\"Cattle\",\"futureComplementField\":\"retained\""
            + ",\"species\":[{\"value\":\"BOV\",\"animalIdentifiers\":[{"
            + "\"earTag\":\"UK000000000001\""
            + ",\"futureIdentifierField\":\"retained\"}]}]}]}"
            + ",\"documents\":[{\"documentType\":\"ITAHC\""
            + ",\"attachmentType\":\"PDF\",\"reference\":\"GBHC1234567890\""
            + ",\"dateOfIssue\":\"2026-07-23\""
            + ",\"futureDocumentField\":{\"nested\":true}}]"
            + ",\"futureTopLevel\":{\"version\":2,\"flags\":[\"A\",\"B\"]}}";
    }
}
