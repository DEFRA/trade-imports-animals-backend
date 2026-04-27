package uk.gov.defra.trade.imports.animals.notification;

import static org.mockito.ArgumentMatchers.any;
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
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

@WebMvcTest(NotificationController.class)
@TestPropertySource(properties = "admin.secret=test-secret")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

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
            .build();

        String expectedReferenceNumber = "DRAFT.IMP.2026.00000001";
        Notification savedNotification = new Notification();
        savedNotification.setId("507f1f77bcf86cd799439011");
        savedNotification.setReferenceNumber(expectedReferenceNumber);
        savedNotification.setOrigin(origin);
        savedNotification.setCommodity(commodity);
        savedNotification.setReasonForImport("PERMANENT");
        savedNotification.setConsignor(consignors().getFirst());
        savedNotification.setDestination(destinations().getFirst());

        when(notificationService.saveOriginOfImport(any(NotificationDto.class)))
            .thenReturn(savedNotification);

        // When & Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notificationDto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439011"))
            .andExpect(jsonPath("$.referenceNumber").value(expectedReferenceNumber))
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
            .andExpect(jsonPath("$.destination.address").value(destinations().getFirst().getAddress()));
    }

    @Test
    void post_shouldAcceptNotificationWithAllOriginFields() throws Exception {
        // Given
        Origin origin = new Origin("FR", "false", "INTERNAL-456");
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(origin)
            .build();

        String expectedReferenceNumber = "DRAFT.IMP.2026.00000042";
        Notification savedNotification = new Notification();
        savedNotification.setId("507f1f77bcf86cd799439012");
        savedNotification.setReferenceNumber(expectedReferenceNumber);
        savedNotification.setOrigin(origin);

        when(notificationService.saveOriginOfImport(any(NotificationDto.class)))
            .thenReturn(savedNotification);

        // When & Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notificationDto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439012"))
            .andExpect(jsonPath("$.referenceNumber").value(expectedReferenceNumber))
            .andExpect(jsonPath("$.origin.countryCode").value("FR"))
            .andExpect(jsonPath("$.origin.internalReference").value("INTERNAL-456"));
    }

    @Test
    void post_shouldAcceptNotificationWithExistingId() throws Exception {
        // Given - updating existing notification
        String existingId = "507f1f77bcf86cd799439011";
        String referenceNumber = "DRAFT.IMP.2026." + existingId;
        Origin origin = new Origin("DE", "true", "UPDATE-REF");
        NotificationDto notificationDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(origin)
            .build();

        Notification savedNotification = new Notification();
        savedNotification.setId(existingId);
        savedNotification.setReferenceNumber(referenceNumber);
        savedNotification.setOrigin(origin);

        when(notificationService.saveOriginOfImport(any(NotificationDto.class)))
            .thenReturn(savedNotification);

        // When & Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notificationDto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(existingId))
            .andExpect(jsonPath("$.referenceNumber").value("DRAFT.IMP.2026." + existingId))
            .andExpect(jsonPath("$.origin.countryCode").value("DE"))
            .andExpect(jsonPath("$.origin.internalReference").value("UPDATE-REF"));
    }

    @Test
    void findAll_shouldReturnEmptyList() throws Exception {
        // Given
        when(notificationService.findAll()).thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/notifications")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void findAll_shouldReturnListOfNotifications() throws Exception {
        // Given
        Origin origin1 = new Origin("GB", "true", "REF-GB-001");
        Notification notification1 = new Notification();
        notification1.setId("507f1f77bcf86cd799439011");
        notification1.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439011");
        notification1.setOrigin(origin1);
        notification1.setCommodity(Commodity.builder().name("Live cattle").build());
        notification1.setConsignor(consignors().getFirst());
        notification1.setDestination(destinations().getFirst());

        Origin origin2 = new Origin("FR", "false", "REF-FR-002");
        Notification notification2 = new Notification();
        notification2.setId("507f1f77bcf86cd799439012");
        notification2.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439012");
        notification2.setOrigin(origin2);
        notification2.setCommodity(Commodity.builder().name("Live sheep").build());
        notification2.setConsignor(consignors().getLast());
        notification2.setDestination(destinations().getLast());

        List<Notification> notifications = Arrays.asList(notification1, notification2);
        when(notificationService.findAll()).thenReturn(notifications);

        // When & Then
        mockMvc.perform(get("/notifications")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("507f1f77bcf86cd799439011"))
            .andExpect(jsonPath("$[0].referenceNumber").value("DRAFT.IMP.2026.507f1f77bcf86cd799439011"))
            .andExpect(jsonPath("$[0].origin.countryCode").value("GB"))
            .andExpect(jsonPath("$[0].commodity.name").value("Live cattle"))
            .andExpect(jsonPath("$[0].consignor.name").value(consignors().getFirst().getName()))
            .andExpect(jsonPath("$[0].consignor.address").value(consignors().getFirst().getAddress()))
            .andExpect(jsonPath("$[0].destination.name").value(destinations().getFirst().getName()))
            .andExpect(jsonPath("$[0].destination.address").value(destinations().getFirst().getAddress()))
            .andExpect(jsonPath("$[1].id").value("507f1f77bcf86cd799439012"))
            .andExpect(jsonPath("$[1].referenceNumber").value("DRAFT.IMP.2026.507f1f77bcf86cd799439012"))
            .andExpect(jsonPath("$[1].origin.countryCode").value("FR"))
            .andExpect(jsonPath("$[1].commodity.name").value("Live sheep"))
            .andExpect(jsonPath("$[1].consignor.name").value(consignors().getLast().getName()))
            .andExpect(jsonPath("$[1].consignor.address").value(consignors().getLast().getAddress()))
            .andExpect(jsonPath("$[1].destination.name").value(destinations().getLast().getName()))
            .andExpect(jsonPath("$[1].destination.address").value(destinations().getLast().getAddress()));
    }

    @Test
    void findAll_shouldReturnSingleNotification() throws Exception {
        // Given
        Origin origin = new Origin("IE", "true", "REF-IE-001");
        Notification notification = new Notification();
        notification.setId("507f1f77bcf86cd799439013");
        notification.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439013");
        notification.setOrigin(origin);
        notification.setCommodity(Commodity.builder().name("Live pigs").build());
        notification.setConsignor(consignors().getFirst());
        notification.setDestination(destinations().getFirst());

        List<Notification> notifications = Collections.singletonList(notification);
        when(notificationService.findAll()).thenReturn(notifications);

        // When & Then
        mockMvc.perform(get("/notifications")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("507f1f77bcf86cd799439013"))
            .andExpect(jsonPath("$[0].referenceNumber").value("DRAFT.IMP.2026.507f1f77bcf86cd799439013"))
            .andExpect(jsonPath("$[0].origin.countryCode").value("IE"))
            .andExpect(jsonPath("$[0].commodity.name").value("Live pigs"))
            .andExpect(jsonPath("$[0].consignor.name").value(consignors().getFirst().getName()))
            .andExpect(jsonPath("$[0].consignor.address").value(consignors().getFirst().getAddress()))
            .andExpect(jsonPath("$[0].destination.name").value(destinations().getFirst().getName()))
            .andExpect(jsonPath("$[0].destination.address").value(destinations().getFirst().getAddress()));
    }

    @Test
    void delete_shouldReturn204_whenAllReferenceNumbersExist() throws Exception {
        // Given
        List<String> referenceNumbers = List.of("DRAFT.IMP.2026.111", "DRAFT.IMP.2026.222");
        doNothing().when(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(HttpHeaders.class));

        // When & Then
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                .content(objectMapper.writeValueAsString(referenceNumbers)))
            .andExpect(status().isNoContent());

        verify(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(HttpHeaders.class));
    }

    @Test
    void delete_shouldReturn404_whenReferenceNumberNotFound() throws Exception {
        // Given
        List<String> referenceNumbers = List.of("DRAFT.IMP.2026.MISSING");
        doThrow(new NotFoundException(
            "Cannot find notifications with reference numbers: DRAFT.IMP.2026.MISSING"))
            .when(notificationService).deleteByReferenceNumbers(eq(referenceNumbers), any(HttpHeaders.class));

        // When & Then — also validates that NotFoundException resolves to 404 (not 500)
        // through the full Spring dispatch chain (GlobalExceptionHandler handler priority check)
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                .content(objectMapper.writeValueAsString(referenceNumbers)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value(
                "Cannot find notifications with reference numbers: DRAFT.IMP.2026.MISSING"));
    }

    @Test
    void delete_shouldReturn400_whenListIsEmpty() throws Exception {
        // When & Then
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Trade-Imports-Animals-Admin-Secret", "test-secret")
                .content("[]"))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).deleteByReferenceNumbers(any(), any());
    }

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
        List<String> referenceNumbers = List.of(
            "DRAFT.IMP.2026.abc123",
            "DRAFT.IMP.2026.xyz456"
        );
        when(notificationService.findAllReferenceNumbers()).thenReturn(referenceNumbers);

        // When & Then
        mockMvc.perform(get("/notifications/reference-numbers")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0]").value("DRAFT.IMP.2026.abc123"))
            .andExpect(jsonPath("$[1]").value("DRAFT.IMP.2026.xyz456"));
    }
}
