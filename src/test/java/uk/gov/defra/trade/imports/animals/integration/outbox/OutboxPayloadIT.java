package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Month;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.Operator;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.notification.Transporter;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;

/**
 * Pins the outbox payload published to Azure Service Bus / Dynamics: the extended operator model
 * (operatorId, telephone, email plus address county, postcode) flows into the event {@code data},
 * and {@code metadata.schemaVersion} is {@code "2"} to declare it (c-021). Shape and version are
 * asserted together so neither can drift silently — the point of the ruling — for both the
 * NOTIFICATION_SUBMITTED and NOTIFICATION_SUBMISSION_AMENDED event types.
 */
class OutboxPayloadIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String HEADER_TRACE_ID = NotificationController.HEADER_TRACE_ID;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    @Test
    void submit_shouldPublishExtendedOperatorModelUnderSchemaVersionTwo() {
        String referenceNumber = createExtendedNotification();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-outbox-payload-submit")
            .exchange()
            .expectStatus().isOk();

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();

        assertThat(event.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(event.getMetadata().getSchemaVersion()).isEqualTo("2");
        assertExtendedOperatorModel(event.getData());
    }

    @Test
    void amend_shouldPublishExtendedOperatorModelUnderSchemaVersionTwo() {
        String referenceNumber = createExtendedNotification();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-outbox-payload-amend")
            .exchange()
            .expectStatus().isOk();

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
            .sorted(Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(events).hasSize(2);

        OutboxEvent amendEvent = events.get(1);
        assertThat(amendEvent.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmissionAmended");
        assertThat(amendEvent.getAggregateVersion()).isEqualTo(2L);
        assertThat(amendEvent.getMetadata().getSchemaVersion()).isEqualTo("2");
        assertExtendedOperatorModel(amendEvent.getData());
    }

    @SuppressWarnings("unchecked")
    private void assertExtendedOperatorModel(Map<String, Object> data) {
        Map<String, Object> consignor = (Map<String, Object>) data.get("consignor");
        assertThat(consignor)
            .containsEntry("operatorId", "OP-CONSIGNOR-1")
            .containsEntry("telephone", "+44 20 7946 0001")
            .containsEntry("email", "consignor@example.com");
        Map<String, Object> consignorAddress = (Map<String, Object>) consignor.get("address");
        assertThat(consignorAddress)
            .containsEntry("county", "Greater London")
            .containsEntry("postcode", "EC1A 1BB");

        Map<String, Object> transport = (Map<String, Object>) data.get("transport");
        Map<String, Object> transporter = (Map<String, Object>) transport.get("transporter");
        assertThat(transporter)
            .containsEntry("operatorId", "OP-TRANSPORTER-1")
            .containsEntry("telephone", "+44 20 7946 0002")
            .containsEntry("email", "transporter@example.com");
        Map<String, Object> transporterAddress = (Map<String, Object>) transporter.get("address");
        assertThat(transporterAddress)
            .containsEntry("county", "West Midlands")
            .containsEntry("postcode", "B1 1AA");
    }

    private String createExtendedNotification() {
        Operator consignor = Operator.builder()
            .operatorId("OP-CONSIGNOR-1")
            .name("British Livestock Ltd")
            .telephone("+44 20 7946 0001")
            .email("consignor@example.com")
            .address(Address.builder()
                .addressLine1("10 Market Street")
                .city("London")
                .county("Greater London")
                .postcode("EC1A 1BB")
                .country("United Kingdom")
                .build())
            .build();

        Transporter transporter = Transporter.builder()
            .operatorId("OP-TRANSPORTER-1")
            .name("Midlands Livestock Transport Ltd")
            .telephone("+44 20 7946 0002")
            .email("transporter@example.com")
            .address(Address.builder()
                .addressLine1("1 Depot Road")
                .city("Birmingham")
                .county("West Midlands")
                .postcode("B1 1AA")
                .country("United Kingdom")
                .build())
            .approvalNumber("UK/BIRM/T2/00104115")
            .type("Commercial")
            .build();

        NotificationDto dto = NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF-EXT-001"))
            .commodity(Commodity.builder().name("Live bovine animals").build())
            .consignor(consignor)
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .transporter(transporter)
                .build())
            .build();

        return webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(dto)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();
    }
}
