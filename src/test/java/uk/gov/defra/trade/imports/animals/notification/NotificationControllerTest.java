package uk.gov.defra.trade.imports.animals.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.notification.NotificationController.HEADER_TRACE_ID;
import static uk.gov.defra.trade.imports.animals.notification.NotificationController.HEADER_USER_ID;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.transporters;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

@WebMvcTest(NotificationController.class)
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085"
})
class NotificationControllerTest {

    private static final String REF_1 = "GBN-AG-26-ABC001";
    private static final String REF_2 = "GBN-AG-26-ABC002";
    private static final String REF_3 = "GBN-AG-26-ABC003";
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private OutboxService outboxService;

    @Nested
    class PostNotification {

        @Test
        void post_shouldCreateNotificationAndReturnReferenceNumber() throws Exception {
            // Given
            Origin origin = new Origin("GB", "true", "CUSTOMER-REF-123");
            Species species = species();
            CommodityComplement complement = new CommodityComplement("LIVE", 5, null, List.of(species));
            Commodity commodity = Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build();
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .commodity(commodity)
                .reasonForImport("PERMANENT")
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .transport(Transport.builder().transporter(transporters().getFirst()).build())
                .consignment(consignments().getFirst())
                .build();

            Notification savedNotification = new Notification();
            savedNotification.setId("507f1f77bcf86cd799439011");
            savedNotification.setReferenceNumber(REF_1);
            savedNotification.setOrigin(origin);
            savedNotification.setCommodity(commodity);
            savedNotification.setReasonForImport("PERMANENT");
            savedNotification.setConsignor(consignors().getFirst());
            savedNotification.setDestination(destinations().getFirst());
            savedNotification.setTransport(Transport.builder().transporter(transporters().getFirst()).build());
            savedNotification.setConsignment(consignments().getFirst());

            when(notificationService.saveOriginOfImport(any(NotificationDto.class)))
                .thenReturn(savedNotification);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(notificationDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439011"))
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.origin.countryCode").value("GB"))
                .andExpect(jsonPath("$.origin.internalReference").value("CUSTOMER-REF-123"))
                .andExpect(jsonPath("$.commodity.name").value("Live bovine animals"))
                .andExpect(jsonPath("$.commodity.commodityComplement[0].typeOfCommodity").value("LIVE"))
                .andExpect(jsonPath("$.commodity.commodityComplement[0].species[0].value").value("BOV"))
                .andExpect(jsonPath("$.commodity.commodityComplement[0].species[0].earTag").value(
                    "UK01234567890"))
                .andExpect(jsonPath("$.commodity.commodityComplement[0].species[0].passport").value(
                    "UK0123456700999"))
                .andExpect(jsonPath("$.reasonForImport").value("PERMANENT"))
                .andExpect(jsonPath("$.consignor.name").value(consignors().getFirst().getName()))
                .andExpect(jsonPath("$.consignor.address").value(consignors().getFirst().getAddress()))
                .andExpect(jsonPath("$.destination.name").value(destinations().getFirst().getName()))
                .andExpect(jsonPath("$.destination.address").value(destinations().getFirst().getAddress()))
                .andExpect(jsonPath("$.transport.transporter.name").value(transporters().getFirst().getName()))
                .andExpect(jsonPath("$.transport.transporter.address").value(transporters().getFirst().getAddress()))
                .andExpect(jsonPath("$.transport.transporter.approvalNumber").value(transporters().getFirst().getApprovalNumber()))
                .andExpect(jsonPath("$.transport.transporter.type").value(transporters().getFirst().getType()))
                .andExpect(jsonPath("$.consignment.contact.name")
                    .value(consignments().getFirst().getContact().getName()))
                .andExpect(jsonPath("$.consignment.contact.address.addressLine1")
                    .value(consignments().getFirst().getContact().getAddress().getAddressLine1()))
                .andExpect(jsonPath("$.consignment.contact.address.country")
                    .value(consignments().getFirst().getContact().getAddress().getCountry()));
        }

        @Test
        void post_shouldAcceptNotificationWithAllOriginFields() throws Exception {
            // Given
            Origin origin = new Origin("FR", "false", "INTERNAL-456");
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .build();

            Notification savedNotification = new Notification();
            savedNotification.setId("507f1f77bcf86cd799439012");
            savedNotification.setReferenceNumber(REF_2);
            savedNotification.setOrigin(origin);

            when(notificationService.saveOriginOfImport(any(NotificationDto.class)))
                .thenReturn(savedNotification);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(notificationDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439012"))
                .andExpect(jsonPath("$.referenceNumber").value(REF_2))
                .andExpect(jsonPath("$.origin.countryCode").value("FR"))
                .andExpect(jsonPath("$.origin.internalReference").value("INTERNAL-456"));
        }

        @Test
        void post_shouldAcceptNotificationWithExistingId() throws Exception {
            // Given - updating existing notification
            String existingId = "507f1f77bcf86cd799439011";
            Origin origin = new Origin("DE", "true", "UPDATE-REF");
            NotificationDto notificationDto = NotificationDto.builder()
                .referenceNumber(REF_3)
                .origin(origin)
                .build();

            Notification savedNotification = new Notification();
            savedNotification.setId(existingId);
            savedNotification.setReferenceNumber(REF_3);
            savedNotification.setOrigin(origin);

            when(notificationService.saveOriginOfImport(any(NotificationDto.class)))
                .thenReturn(savedNotification);

            // When & Then
            mockMvc.perform(post("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(notificationDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingId))
                .andExpect(jsonPath("$.referenceNumber").value(REF_3))
                .andExpect(jsonPath("$.origin.countryCode").value("DE"))
                .andExpect(jsonPath("$.origin.internalReference").value("UPDATE-REF"));
        }
    }

    @Nested
    class SubmitNotification {

        @Test
        void submit_shouldReturn200WithSubmittedNotification() throws Exception {
            // Given
            Notification submitted = new Notification();
            submitted.setId("notif-id-001");
            submitted.setReferenceNumber(REF_1);
            submitted.setStatus(NotificationStatus.SUBMITTED);

            when(notificationService.submitNotification(eq(REF_1), anyString()))
                .thenReturn(submitted);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        }

        @Test
        void submit_shouldPassTraceIdAsCorrelationId() throws Exception {
            // Given
            Notification submitted = new Notification();
            submitted.setId("notif-id-001");
            submitted.setReferenceNumber(REF_1);
            submitted.setStatus(NotificationStatus.SUBMITTED);

            when(notificationService.submitNotification(eq(REF_1), eq("trace-abc")))
                .thenReturn(submitted);

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", REF_1)
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

            verify(notificationService).submitNotification(REF_1, "trace-abc");
        }

        @Test
        void submit_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            // Given
            when(notificationService.submitNotification(eq(NONEXISTENT_REF), anyString()))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            // When & Then
            mockMvc.perform(post("/notifications/{referenceNumber}/submit", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_shouldReturnEmptyPage() throws Exception {
            // Given
            when(notificationService.findAll(0)).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 0, 54, 0, 0, 0));

            // When & Then
            mockMvc.perform(get("/notifications")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(54))
                .andExpect(jsonPath("$.numberOfElements").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        void findAll_shouldReturnPageOfNotifications() throws Exception {
            // Given
            Notification notification1 = new Notification();
            notification1.setId("507f1f77bcf86cd799439011");
            notification1.setReferenceNumber(REF_1);
            notification1.setOrigin(new Origin("GB", "true", "REF-GB-001"));
            notification1.setCommodity(Commodity.builder().name("Live cattle").build());
            notification1.setConsignor(consignors().getFirst());
            notification1.setDestination(destinations().getFirst());
            notification1.setTransport(Transport.builder().transporter(transporters().getFirst()).build());

            Notification notification2 = new Notification();
            notification2.setId("507f1f77bcf86cd799439012");
            notification2.setReferenceNumber(REF_2);
            notification2.setOrigin(new Origin("FR", "false", "REF-FR-002"));
            notification2.setCommodity(Commodity.builder().name("Live sheep").build());
            notification2.setConsignor(consignors().getLast());
            notification2.setDestination(destinations().getLast());
            notification2.setTransport(Transport.builder().transporter(transporters().getLast()).build());

            when(notificationService.findAll(0)).thenReturn(
                new NotificationPageResponse(List.of(notification1, notification2), 0, 54, 2, 2, 1));

            // When & Then
            mockMvc.perform(get("/notifications")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value("507f1f77bcf86cd799439011"))
                .andExpect(jsonPath("$.content[0].referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.content[0].origin.countryCode").value("GB"))
                .andExpect(jsonPath("$.content[0].commodity.name").value("Live cattle"))
                .andExpect(jsonPath("$.content[1].id").value("507f1f77bcf86cd799439012"))
                .andExpect(jsonPath("$.content[1].referenceNumber").value(REF_2))
                .andExpect(jsonPath("$.content[1].origin.countryCode").value("FR"))
                .andExpect(jsonPath("$.content[1].commodity.name").value("Live sheep"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        void findAll_shouldPassPageParam() throws Exception {
            // Given
            when(notificationService.findAll(2)).thenReturn(
                new NotificationPageResponse(Collections.emptyList(), 2, 54, 0, 120, 3));

            // When & Then
            mockMvc.perform(get("/notifications")
                    .param("page", "2")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(120))
                .andExpect(jsonPath("$.totalPages").value(3));
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_shouldReturn204_whenAllReferenceNumbersExist() throws Exception {
            // Given
            List<String> referenceNumbers = List.of(REF_1, REF_2);
            doNothing().when(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(AuditContext.class));

            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isNoContent());

            verify(notificationService).deleteByReferenceNumbers(referenceNumbers, new AuditContext("trace-abc", "user-123"));
        }

        @Test
        void delete_shouldReturn404_whenReferenceNumberNotFound() throws Exception {
            // Given
            List<String> referenceNumbers = List.of(NONEXISTENT_REF);
            doThrow(new NotFoundException(
                "Cannot find notifications with reference numbers: " + NONEXISTENT_REF))
                .when(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(AuditContext.class));

            // When & Then — also validates that NotFoundException resolves to 404 (not 500)
            // through the full Spring dispatch chain (GlobalExceptionHandler handler priority check)
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notifications with reference numbers: " + NONEXISTENT_REF));
        }

        @Test
        void delete_shouldReturn400_whenListIsEmpty() throws Exception {
            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .header(HEADER_USER_ID, "user-123")
                    .content("[]"))
                .andExpect(status().isBadRequest());

            verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
        }

        @Test
        void delete_shouldReturn400_whenTraceIdHeaderIsMissing() throws Exception {
            // Given — x-cdp-request-id absent; Spring rejects with 400 before service is called
            List<String> referenceNumbers = List.of(REF_1);

            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_USER_ID, "user-123")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isBadRequest());

            verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
        }

        @Test
        void delete_shouldReturn400_whenUserIdHeaderIsMissing() throws Exception {
            // Given — User-Id absent; Spring rejects with 400 before service is called
            List<String> referenceNumbers = List.of(REF_1);

            // When & Then
            mockMvc.perform(delete("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                    .header(HEADER_TRACE_ID, "trace-abc")
                    .content(objectMapper.writeValueAsString(referenceNumbers)))
                .andExpect(status().isBadRequest());

            verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
        }
    }

    @Nested
    class FindByRef {

        @Test
        void findByRef_shouldReturn200WithHydratedNotification() throws Exception {
            // Given
            Origin origin = new Origin("GB", "true", "REF-001");

            AccompanyingDocumentDto document = new AccompanyingDocumentDto(
                "doc-id-001", REF_1, "upload-abc-123",
                DocumentType.ITAHC, "UKGB2026001",
                /* dateOfIssue */ null, ScanStatus.COMPLETE,
                /* files */ Collections.emptyList(), /* created */ null, /* updated */ null);

            NotificationResponse response = NotificationResponse.builder()
                .id("notif-id-001")
                .referenceNumber(REF_1)
                .origin(origin)
                .commodity(Commodity.builder().name("Live bovine animals").build())
                .reasonForImport("PERMANENT")
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .consignment(consignments().getFirst())
                .accompanyingDocuments(List.of(document))
                .build();

            when(notificationService.findByRef(REF_1)).thenReturn(response);

            // When / Then
            mockMvc.perform(get("/notifications/{referenceNumber}", REF_1)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_1))
                .andExpect(jsonPath("$.origin.countryCode").value("GB"))
                .andExpect(jsonPath("$.commodity.name").value("Live bovine animals"))
                .andExpect(jsonPath("$.consignor.name").value(consignors().getFirst().getName()))
                .andExpect(jsonPath("$.destination.name").value(destinations().getFirst().getName()))
                .andExpect(jsonPath("$.consignment.contact.name")
                    .value(consignments().getFirst().getContact().getName()))
                .andExpect(jsonPath("$.consignment.contact.address.addressLine1")
                    .value(consignments().getFirst().getContact().getAddress().getAddressLine1()))
                .andExpect(jsonPath("$.consignment.contact.address.country")
                    .value(consignments().getFirst().getContact().getAddress().getCountry()))
                .andExpect(jsonPath("$.accompanyingDocuments").isArray())
                .andExpect(jsonPath("$.accompanyingDocuments.length()").value(1))
                .andExpect(jsonPath("$.accompanyingDocuments[0].uploadId").value("upload-abc-123"))
                .andExpect(jsonPath("$.accompanyingDocuments[0].scanStatus").value("COMPLETE"));
        }

        @Test
        void findByRef_shouldReturn200WithEmptyDocumentsList() throws Exception {
            // Given
            Origin origin = new Origin("GB", "true", "REF-002");

            NotificationResponse response = NotificationResponse.builder()
                .id("notif-id-002")
                .referenceNumber(REF_2)
                .origin(origin)
                .commodity(Commodity.builder().name("Live sheep").build())
                .reasonForImport("PERMANENT")
                .accompanyingDocuments(Collections.emptyList())
                .build();

            when(notificationService.findByRef(REF_2)).thenReturn(response);

            // When / Then
            mockMvc.perform(get("/notifications/{referenceNumber}", REF_2)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REF_2))
                .andExpect(jsonPath("$.accompanyingDocuments").isArray())
                .andExpect(jsonPath("$.accompanyingDocuments").isEmpty());
        }

        @Test
        void findByRef_shouldReturn404_whenReferenceNumberUnknown() throws Exception {
            // Given
            when(notificationService.findByRef(NONEXISTENT_REF))
                .thenThrow(new NotFoundException(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));

            // When / Then
            mockMvc.perform(get("/notifications/{referenceNumber}", NONEXISTENT_REF)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                    "Cannot find notification with reference number: " + NONEXISTENT_REF));
        }
    }

    @Nested
    class FindAllReferenceNumbers {

        @Test
        void findAllReferenceNumbers_shouldReturnEmptyList() throws Exception {
            // Given
            when(notificationService.findAllReferenceNumbers()).thenReturn(Collections.emptyList());

            // When & Then
            mockMvc.perform(get("/notifications/reference-numbers")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        void findAllReferenceNumbers_shouldReturnListOfReferenceNumbers() throws Exception {
            // Given
            List<String> referenceNumbers = List.of(REF_1, REF_2);
            when(notificationService.findAllReferenceNumbers()).thenReturn(referenceNumbers);

            // When & Then
            mockMvc.perform(get("/notifications/reference-numbers")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value(REF_1))
                .andExpect(jsonPath("$[1]").value(REF_2));
        }
    }

    @Nested
    class GetOutboxEvents {

        @Test
        void getOutboxEvents_shouldReturnEventsForReferenceNumber() throws Exception {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            List<OutboxEvent> events = List.of(
                OutboxEvent.builder().aggregateVersion(1L)
                    .eventType("uk.gov.defra.imports.notification.NotificationSubmitted").build(),
                OutboxEvent.builder().aggregateVersion(2L)
                    .eventType("uk.gov.defra.imports.notification.NotificationSubmitted").build()
            );
            when(outboxService.findByReferenceNumber(referenceNumber)).thenReturn(events);

            // When & Then
            mockMvc.perform(get("/notifications/{ref}/outbox-events", referenceNumber)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].aggregateVersion").value(1))
                .andExpect(jsonPath("$[1].aggregateVersion").value(2));
        }

        @Test
        void getOutboxEvents_shouldReturnEmptyList_whenNoEventsExist() throws Exception {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(outboxService.findByReferenceNumber(referenceNumber)).thenReturn(List.of());

            // When & Then
            mockMvc.perform(get("/notifications/{ref}/outbox-events", referenceNumber)
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        }
    }
}
