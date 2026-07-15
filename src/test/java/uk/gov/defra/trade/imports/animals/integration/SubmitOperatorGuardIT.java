package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Operator;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;

/**
 * Integration test for the M1.5 submit guard (design §4.5, c-013/c-017/c-018). MockServer stands in
 * for the operators service. Pins the fail-closed submit stance end to end: a tombstoned party and a
 * 404 party each yield a 400 validation problem keyed by the party field but with distinct copy; an
 * operators-service outage yields a 502; and a successful submit transitions the status only — the
 * embedded copy that was selected at capture time is what gets frozen, even when the operators
 * service now reports a different value (the accepted staleness, c-017).
 */
class SubmitOperatorGuardIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String CRN_HEADER = "Trade-Imports-Crn";
    private static final String CRN = "GBCRN123";
    private static final String DELETED_MESSAGE = "Operator has been deleted — select a replacement";
    private static final String UNRESOLVED_MESSAGE = "Operator could not be verified — select it again";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    private Operator operator(String operatorId, String name) {
        return Operator.builder()
            .operatorId(operatorId)
            .name(name)
            .address(Address.builder().addressLine1("1 Test Street").country("United Kingdom").build())
            .build();
    }

    private void persist(String referenceNumber, Notification.NotificationBuilder<?, ?> extra) {
        notificationRepository.save(extra
            .referenceNumber(referenceNumber)
            .origin(new Origin("GB", "true", "REF"))
            .status(NotificationStatus.DRAFT)
            .build());
    }

    private void stubOperator(String operatorId, int statusCode, String body) {
        usingStub()
            .when(request().withMethod("GET").withPath(".*/" + operatorId))
            .respond(body == null
                ? response().withStatusCode(statusCode)
                : response()
                    .withStatusCode(statusCode)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body));
    }

    @Test
    void submit_shouldReturn400KeyedByParty_whenOperatorDeleted() {
        // Given — a DRAFT whose consignor references a tombstoned operator
        persist("GBN-AG-26-SDA001", Notification.builder().consignor(operator("OP-SDL", "Deleted Co")));
        stubOperator("OP-SDL", 200, "{\"id\":\"OP-SDL\",\"status\":\"DELETED\"}");

        // When / Then — 400 validation problem keyed by the party field with the deleted copy
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", "GBN-AG-26-SDA001")
            .header(CRN_HEADER, CRN)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.type").value(Matchers.containsString("validation-error"))
            .jsonPath("$.errors.consignor[0]").isEqualTo(DELETED_MESSAGE);

        // And the status was not transitioned and no outbox event was written (fail closed)
        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-SDA001").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void submit_shouldReturn400WithDistinctMessage_whenOperatorUnresolved() {
        // Given — a DRAFT whose importer 404s (unknown / another crn's operator)
        persist("GBN-AG-26-SRA001", Notification.builder().importer(operator("OP-SUR", "Unknown Co")));
        stubOperator("OP-SUR", 404, null);

        // When / Then — 400 keyed by party with the UNRESOLVED copy, NOT the deleted copy (c-018)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", "GBN-AG-26-SRA001")
            .header(CRN_HEADER, CRN)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.type").value(Matchers.containsString("validation-error"))
            .jsonPath("$.errors.importer[0]").isEqualTo(UNRESOLVED_MESSAGE);

        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-SRA001").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void submit_shouldFreezeStaleCopy_whenOperatorActiveButEdited() {
        // Given — a DRAFT whose consignor was captured as "Original Name"; the operators service now
        // reports a DIFFERENT name (the operator was edited since selection)
        Operator storedConsignor = operator("OP-STA", "Original Name");
        persist("GBN-AG-26-STA001", Notification.builder().consignor(storedConsignor));
        stubOperator("OP-STA", 200, "{\"id\":\"OP-STA\",\"status\":\"ACTIVE\",\"name\":\"SERVER EDITED NAME\"}");

        // When — submit succeeds
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", "GBN-AG-26-STA001")
            .header(CRN_HEADER, CRN)
            .exchange()
            .expectStatus().isOk();

        // Then — the submitted record carries the ORIGINAL selection-time copy, never the server's
        // edited value (c-017: transition only, no re-hydration)
        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-STA001").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(reloaded.getConsignor()).isEqualTo(storedConsignor);
        assertThat(reloaded.getConsignor().getName()).isEqualTo("Original Name");
    }

    @Test
    void submit_shouldReturn502_whenOperatorsServiceUnavailable() {
        // Given — the operators service errors (504 → neither 2xx nor 404 → UNAVAILABLE)
        Operator storedConsignor = operator("OP-SDN", "Some Co");
        persist("GBN-AG-26-SDN001", Notification.builder().consignor(storedConsignor));
        stubOperator("OP-SDN", 504, null);

        // When / Then — fail closed on submit: 502 upstream-error, status untransitioned
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", "GBN-AG-26-SDN001")
            .header(CRN_HEADER, CRN)
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo(502)
            .jsonPath("$.type").value(Matchers.containsString("upstream-error"));

        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-SDN001").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }
}
