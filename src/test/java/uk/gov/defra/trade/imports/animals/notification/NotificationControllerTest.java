package uk.gov.defra.trade.imports.animals.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @Test
    void post_shouldCreateNotificationAndReturnReferenceNumber() throws Exception {
        // Given
        Origin origin = new Origin("GB", "true", "CUSTOMER-REF-123");
        Notification notification = new Notification();
        notification.setOrigin(origin);
        notification.setCommodity("Live bovine animals");

        String expectedReferenceNumber = "DRAFT.IMP.2026.00000001";
        Notification savedNotification = new Notification();
        savedNotification.setId("507f1f77bcf86cd799439011");
        savedNotification.setReferenceNumber(expectedReferenceNumber);
        savedNotification.setOrigin(origin);
        savedNotification.setCommodity("Live bovine animals");

        when(notificationService.saveOriginOfImport(any(Notification.class)))
            .thenReturn(savedNotification);

        // When & Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notification)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("507f1f77bcf86cd799439011"))
            .andExpect(jsonPath("$.referenceNumber").value(expectedReferenceNumber))
            .andExpect(jsonPath("$.origin.countryCode").value("GB"))
            .andExpect(jsonPath("$.origin.internalReference").value("CUSTOMER-REF-123"))
            .andExpect(jsonPath("$.commodity").value("Live bovine animals"));
    }

    @Test
    void post_shouldAcceptNotificationWithAllOriginFields() throws Exception {
        // Given
        Origin origin = new Origin("FR", "false", "INTERNAL-456");
        Notification notification = new Notification();
        notification.setOrigin(origin);

        String expectedReferenceNumber = "DRAFT.IMP.2026.00000042";
        Notification savedNotification = new Notification();
        savedNotification.setId("507f1f77bcf86cd799439012");
        savedNotification.setReferenceNumber(expectedReferenceNumber);
        savedNotification.setOrigin(origin);

        when(notificationService.saveOriginOfImport(any(Notification.class)))
            .thenReturn(savedNotification);

        // When & Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notification)))
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
        Origin origin = new Origin("DE", "true", "UPDATE-REF");
        Notification notification = new Notification();
        notification.setId(existingId);
        notification.setOrigin(origin);
        notification.setReferenceNumber("DRAFT.IMP.2026." + existingId);

        Notification savedNotification = new Notification();
        savedNotification.setId(existingId);
        savedNotification.setReferenceNumber("DRAFT.IMP.2026." + existingId);
        savedNotification.setOrigin(origin);

        when(notificationService.saveOriginOfImport(any(Notification.class)))
            .thenReturn(savedNotification);

        // When & Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notification)))
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
        notification1.setCommodity("Live cattle");

        Origin origin2 = new Origin("FR", "false", "REF-FR-002");
        Notification notification2 = new Notification();
        notification2.setId("507f1f77bcf86cd799439012");
        notification2.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439012");
        notification2.setOrigin(origin2);
        notification2.setCommodity("Live sheep");

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
            .andExpect(jsonPath("$[0].commodity").value("Live cattle"))
            .andExpect(jsonPath("$[1].id").value("507f1f77bcf86cd799439012"))
            .andExpect(jsonPath("$[1].referenceNumber").value("DRAFT.IMP.2026.507f1f77bcf86cd799439012"))
            .andExpect(jsonPath("$[1].origin.countryCode").value("FR"))
            .andExpect(jsonPath("$[1].commodity").value("Live sheep"));
    }

    @Test
    void findAll_shouldReturnSingleNotification() throws Exception {
        // Given
        Origin origin = new Origin("IE", "true", "REF-IE-001");
        Notification notification = new Notification();
        notification.setId("507f1f77bcf86cd799439013");
        notification.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439013");
        notification.setOrigin(origin);
        notification.setCommodity("Live pigs");

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
            .andExpect(jsonPath("$[0].commodity").value("Live pigs"));
    }
}
