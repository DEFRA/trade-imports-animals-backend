package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository);
    }

    @Test
    void saveOriginOfImport_shouldSaveNewNotificationAndGenerateReferenceNumber() {
        // Given - new notification without ID
        Origin origin = new Origin("GB", "true", "REF123");
        Notification newNotification = new Notification();
        newNotification.setOrigin(origin);

        // Simulate MongoDB auto-generating ID on first save
        Notification savedWithId = new Notification();
        savedWithId.setId(123L);
        savedWithId.setOrigin(origin);

        // Simulate second save with reference number
        Notification savedWithRef = new Notification();
        savedWithRef.setId(123L);
        savedWithRef.setOrigin(origin);
        savedWithRef.setReferenceNumber("DRAFT.CHEDA." + LocalDate.now().getYear() + ".00000123");

        when(notificationRepository.save(any(Notification.class)))
            .thenReturn(savedWithId)  // First save returns notification with ID
            .thenReturn(savedWithRef); // Second save returns notification with reference number

        // When
        String referenceNumber = notificationService.saveOriginOfImport(newNotification);

        // Then
        assertThat(referenceNumber).isNotNull();
        assertThat(referenceNumber).startsWith("DRAFT.CHEDA." + LocalDate.now().getYear() + ".");
        assertThat(referenceNumber).endsWith("00000123");
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void saveOriginOfImport_shouldUpdateExistingNotification() {
        // Given - existing notification with ID and reference number
        Origin origin = new Origin("FR", "false", "REF456");
        Notification existingNotification = new Notification();
        existingNotification.setId(456L);
        existingNotification.setReferenceNumber("DRAFT.CHEDA.2026.00000456");
        existingNotification.setOrigin(origin);

        when(notificationRepository.save(existingNotification)).thenReturn(existingNotification);

        // When
        String referenceNumber = notificationService.saveOriginOfImport(existingNotification);

        // Then
        assertThat(referenceNumber).isEqualTo("DRAFT.CHEDA.2026.00000456");
        verify(notificationRepository, times(1)).save(existingNotification);
    }

    @Test
    void saveOriginOfImport_shouldPadIdWith8Digits() {
        // Given - new notification with small ID
        Origin origin = new Origin("DE", "true", "REF789");
        Notification newNotification = new Notification();
        newNotification.setOrigin(origin);

        Notification savedWithId = new Notification();
        savedWithId.setId(7L);  // Small ID
        savedWithId.setOrigin(origin);

        Notification savedWithRef = new Notification();
        savedWithRef.setId(7L);
        savedWithRef.setOrigin(origin);
        savedWithRef.setReferenceNumber("DRAFT.CHEDA." + LocalDate.now().getYear() + ".00000007");

        when(notificationRepository.save(any(Notification.class)))
            .thenReturn(savedWithId)
            .thenReturn(savedWithRef);

        // When
        String referenceNumber = notificationService.saveOriginOfImport(newNotification);

        // Then
        assertThat(referenceNumber).endsWith("00000007");
    }

    @Test
    void saveOriginOfImport_shouldIncludeCurrentYearInReferenceNumber() {
        // Given
        Origin origin = new Origin("IT", "false", "REF999");
        Notification newNotification = new Notification();
        newNotification.setOrigin(origin);

        Notification savedWithId = new Notification();
        savedWithId.setId(999L);
        savedWithId.setOrigin(origin);

        int currentYear = LocalDate.now().getYear();
        Notification savedWithRef = new Notification();
        savedWithRef.setId(999L);
        savedWithRef.setOrigin(origin);
        savedWithRef.setReferenceNumber("DRAFT.CHEDA." + currentYear + ".00000999");

        when(notificationRepository.save(any(Notification.class)))
            .thenReturn(savedWithId)
            .thenReturn(savedWithRef);

        // When
        String referenceNumber = notificationService.saveOriginOfImport(newNotification);

        // Then
        assertThat(referenceNumber).contains(String.valueOf(currentYear));
    }
}
