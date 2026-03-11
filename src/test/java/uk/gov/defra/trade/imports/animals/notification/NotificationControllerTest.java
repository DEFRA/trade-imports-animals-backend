package uk.gov.defra.trade.imports.animals.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

        String expectedReferenceNumber = "DRAFT.CHEDA.2026.00000001";
        when(notificationService.saveOriginOfImport(any(Notification.class)))
            .thenReturn(expectedReferenceNumber);

        // When & Then
        mockMvc.perform(post("/notification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notification)))
            .andExpect(status().isOk())
            .andExpect(content().string(expectedReferenceNumber));
    }

    @Test
    void post_shouldAcceptNotificationWithAllOriginFields() throws Exception {
        // Given
        Origin origin = new Origin("FR", "false", "INTERNAL-456");
        Notification notification = new Notification();
        notification.setOrigin(origin);

        String expectedReferenceNumber = "DRAFT.CHEDA.2026.00000042";
        when(notificationService.saveOriginOfImport(any(Notification.class)))
            .thenReturn(expectedReferenceNumber);

        // When & Then
        mockMvc.perform(post("/notification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notification)))
            .andExpect(status().isOk())
            .andExpect(content().string(expectedReferenceNumber));
    }

    @Test
    void post_shouldAcceptNotificationWithExistingId() throws Exception {
        // Given - updating existing notification
        Origin origin = new Origin("DE", "true", "UPDATE-REF");
        Notification notification = new Notification();
        notification.setId(123L);
        notification.setOrigin(origin);
        notification.setReferenceNumber("DRAFT.CHEDA.2026.00000123");

        when(notificationService.saveOriginOfImport(any(Notification.class)))
            .thenReturn("DRAFT.CHEDA.2026.00000123");

        // When & Then
        mockMvc.perform(post("/notification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(notification)))
            .andExpect(status().isOk())
            .andExpect(content().string("DRAFT.CHEDA.2026.00000123"));
    }
}
