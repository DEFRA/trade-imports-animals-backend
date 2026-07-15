package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationResponse;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Operator;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.notification.Transporter;

/**
 * Integration test for the EUDPA-293 existence-check detection surface on a DRAFT/AMEND read
 * (design §4.3/§4.4). MockServer stands in for the operators service. Pins the c-017/c-018 rulings
 * end to end: a tombstoned operator surfaces under {@code deletedOperatorFields}, a 404 under
 * {@code unresolvedOperatorFields} (never folded into "deleted"), the stored document is byte-for-byte
 * unchanged after the read (no hydration), SUBMITTED is never checked, and an operators outage
 * degrades open (200, both arrays absent, {@code OperatorCheckFailure} metric).
 */
class NotificationOperatorCheckIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String CRN_HEADER = "Trade-Imports-Crn";
    private static final String CRN = "GBCRN123";

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    private Operator operator(String operatorId, String name) {
        return Operator.builder()
            .operatorId(operatorId)
            .name(name)
            .telephone("01234 567890")
            .email("ops@example.com")
            .address(Address.builder()
                .addressLine1("1 Test Street")
                .city("Testville")
                .county("Testshire")
                .postcode("TE1 1ST")
                .country("United Kingdom")
                .build())
            .build();
    }

    private Notification persist(String referenceNumber, NotificationStatus status, Notification.NotificationBuilder<?, ?> extra) {
        Notification notification = extra
            .referenceNumber(referenceNumber)
            .origin(new Origin("GB", "true", "REF"))
            .status(status)
            .build();
        return notificationRepository.save(notification);
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

    private NotificationResponse getNotification(String referenceNumber) {
        return webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}", referenceNumber)
            .header(CRN_HEADER, CRN)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationResponse.class)
            .returnResult().getResponseBody();
    }

    @Test
    void get_shouldFlagDeletedOperator_underPartyKey_andNotHydrate() {
        // Given — a DRAFT whose consignor references a tombstoned operator; the server returns a
        // DIFFERENT name in its body to prove the check discards field values (c-017)
        Operator storedConsignor = operator("OP-DET", "Original Consignor");
        persist("GBN-AG-26-DET001", NotificationStatus.DRAFT,
            Notification.builder().consignor(storedConsignor));
        stubOperator("OP-DET", 200, "{\"id\":\"OP-DET\",\"status\":\"DELETED\",\"name\":\"SERVER NAME\"}");

        // When
        NotificationResponse response = getNotification("GBN-AG-26-DET001");

        // Then — flagged under the party key, unresolved empty (the check ran)
        assertThat(response.deletedOperatorFields()).containsExactly("consignor");
        assertThat(response.unresolvedOperatorFields()).isEmpty();
        // And the returned consignor keeps its stored values — never the server's "SERVER NAME"
        assertThat(response.consignor().getName()).isEqualTo("Original Consignor");
        assertThat(response.consignor().getOperatorId()).isEqualTo("OP-DET");

        // And the stored document is unchanged
        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-DET001").orElseThrow();
        assertThat(reloaded.getConsignor()).isEqualTo(storedConsignor);
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DRAFT);
    }

    @Test
    void get_shouldFlag404OperatorAsUnresolved_notDeleted() {
        // Given — a DRAFT whose importer 404s (unknown id, or another crn's operator)
        Operator storedImporter = operator("OP-NF4", "Original Importer");
        persist("GBN-AG-26-NF4001", NotificationStatus.DRAFT,
            Notification.builder().importer(storedImporter));
        stubOperator("OP-NF4", 404, null);

        // When
        NotificationResponse response = getNotification("GBN-AG-26-NF4001");

        // Then — a 404 is NOT a deletion: it lands in unresolved, and deleted stays empty
        assertThat(response.unresolvedOperatorFields()).containsExactly("importer");
        assertThat(response.deletedOperatorFields()).isEmpty();

        // And the stored document is unchanged
        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-NF4001").orElseThrow();
        assertThat(reloaded.getImporter()).isEqualTo(storedImporter);
    }

    @Test
    void get_shouldFlagDeletedTransporter_underTransporterKey() {
        // Given — the transporter (transport.transporter, not a NotificationBase party) is deleted
        Transporter transporter = Transporter.builder()
            .operatorId("OP-TRA")
            .name("Original Haulier")
            .address(Address.builder().addressLine1("2 Depot Road").country("UK").build())
            .approvalNumber("UK/T2/001")
            .type("Commercial")
            .build();
        Transport transport = Transport.builder().portOfEntry("GBFXT").transporter(transporter).build();
        persist("GBN-AG-26-TRA001", NotificationStatus.DRAFT,
            Notification.builder().transport(transport));
        stubOperator("OP-TRA", 200, "{\"id\":\"OP-TRA\",\"status\":\"DELETED\"}");

        // When
        NotificationResponse response = getNotification("GBN-AG-26-TRA001");

        // Then — keyed under the explicit 'transporter' key
        assertThat(response.deletedOperatorFields()).containsExactly("transporter");
        assertThat(response.unresolvedOperatorFields()).isEmpty();

        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-TRA001").orElseThrow();
        assertThat(reloaded.getTransport().getTransporter()).isEqualTo(transporter);
    }

    @Test
    void get_shouldCheckAmend_identicallyToDraft() {
        // Given — c-013: AMEND is treated identically to DRAFT on the read-check
        persist("GBN-AG-26-AMD001", NotificationStatus.AMEND,
            Notification.builder().consignee(operator("OP-DET", "Original Consignee")));
        stubOperator("OP-DET", 200, "{\"id\":\"OP-DET\",\"status\":\"DELETED\"}");

        // When
        NotificationResponse response = getNotification("GBN-AG-26-AMD001");

        // Then
        assertThat(response.deletedOperatorFields()).containsExactly("consignee");
        assertThat(response.unresolvedOperatorFields()).isEmpty();
    }

    @Test
    void get_shouldNotCheckSubmitted_leavingArraysAbsent() {
        // Given — a SUBMITTED notification is frozen by status (c-003) and never checked, even
        // though its consignor references a would-be-deleted operator
        Operator storedConsignor = operator("OP-DET", "Frozen Consignor");
        persist("GBN-AG-26-SBM001", NotificationStatus.SUBMITTED,
            Notification.builder().consignor(storedConsignor));
        stubOperator("OP-DET", 200, "{\"id\":\"OP-DET\",\"status\":\"DELETED\"}");

        // When
        NotificationResponse response = getNotification("GBN-AG-26-SBM001");

        // Then — both arrays ABSENT (no check ran)
        assertThat(response.deletedOperatorFields()).isNull();
        assertThat(response.unresolvedOperatorFields()).isNull();

        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-SBM001").orElseThrow();
        assertThat(reloaded.getConsignor()).isEqualTo(storedConsignor);
    }

    @Test
    void get_shouldDegradeOpen_whenOperatorsServiceUnavailable() {
        // Given — the operators service errors (5xx that is neither 2xx nor 404 → UNAVAILABLE)
        Operator storedConsignor = operator("OP-DWN", "Some Consignor");
        persist("GBN-AG-26-DWN001", NotificationStatus.DRAFT,
            Notification.builder().consignor(storedConsignor));
        stubOperator("OP-DWN", 503, null);

        // When
        NotificationResponse response = getNotification("GBN-AG-26-DWN001");

        // Then — degrade open: 200 with both arrays ABSENT (no claim, not verified-clean).
        // The OperatorCheckFailure metric is exercised at the unit level (NotificationServiceTest);
        // metrics are deliberately disabled in the integration-test profile, so the composite
        // registry records nothing to assert on here.
        assertThat(response.deletedOperatorFields()).isNull();
        assertThat(response.unresolvedOperatorFields()).isNull();

        // And the stored document is untouched by the failed check
        Notification reloaded = notificationRepository.findByReferenceNumber("GBN-AG-26-DWN001").orElseThrow();
        assertThat(reloaded.getConsignor()).isEqualTo(storedConsignor);
    }
}
