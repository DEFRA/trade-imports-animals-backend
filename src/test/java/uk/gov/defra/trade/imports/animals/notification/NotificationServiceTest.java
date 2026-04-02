package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditRepository auditRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, auditRepository);
    }

    private HttpHeaders headersWithAuditFields() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-cdp-request-id", "test-trace-id");
        headers.add("User-Id", "test-user-id");
        return headers;
    }

    @Test
    void saveOriginOfImport_shouldSaveNewNotificationAndGenerateReferenceNumber() {
        // Given - new notification without referenceNumber
        Origin origin = new Origin("GB", "true", "REF123");
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(origin)
            .build();

        // Simulate MongoDB auto-generating ID on first save
        String generatedId = "507f1f77bcf86cd799439011";
        Notification savedWithId = new Notification();
        savedWithId.setId(generatedId);
        savedWithId.setOrigin(origin);

        // Simulate second save with reference number
        Notification savedWithRef = new Notification();
        savedWithRef.setId(generatedId);
        savedWithRef.setOrigin(origin);
        savedWithRef.setReferenceNumber("DRAFT.IMP." + LocalDate.now().getYear() + "." + generatedId);

        when(notificationRepository.save(any(Notification.class)))
            .thenReturn(savedWithId)  // First save returns notification with ID
            .thenReturn(savedWithRef); // Second save returns notification with reference number

        // When
        Notification result = notificationService.saveOriginOfImport(notificationDto);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isNotNull();
        assertThat(result.getReferenceNumber()).startsWith("DRAFT.IMP." + LocalDate.now().getYear() + ".");
        assertThat(result.getReferenceNumber()).endsWith(generatedId);
        assertThat(result.getId()).isEqualTo(generatedId);
        assertThat(result.getOrigin()).isEqualTo(origin);
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void saveOriginOfImport_shouldUpdateExistingNotification() {
        // Given - existing notification with ID and reference number
        String existingId = "507f191e810c19729de860ea";
        String referenceNumber = "DRAFT.IMP.2026." + existingId;
        Origin origin = new Origin("FR", "false", "REF456");

        Notification existingNotification = new Notification();
        existingNotification.setId(existingId);
        existingNotification.setReferenceNumber(referenceNumber);
        existingNotification.setOrigin(origin);

        when(notificationRepository.findByReferenceNumber(referenceNumber))
            .thenReturn(Optional.of(existingNotification));

        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(origin)
            .commodity("Fish")
            .build();

        Notification updatedNotification = Notification.builder()
            .id(existingId)
            .referenceNumber(referenceNumber)
            .origin(origin)
            .commodity("Fish")
            .build();

        when(notificationRepository.save(any(Notification.class))).thenReturn(updatedNotification);

        // When
        Notification result = notificationService.saveOriginOfImport(updateDto);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isEqualTo("DRAFT.IMP.2026." + existingId);
        assertThat(result.getId()).isEqualTo(existingId);
        assertThat(result.getOrigin()).isEqualTo(origin);
        assertThat(result.getCommodity()).isEqualTo("Fish");
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void saveOriginOfImport_shouldUseFullObjectIdInReferenceNumber() {
        // Given - new notification
        Origin origin = new Origin("DE", "true", "REF789");
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(origin)
            .build();

        String generatedId = "65a1b2c3d4e5f67890abcdef";
        Notification savedWithId = new Notification();
        savedWithId.setId(generatedId);
        savedWithId.setOrigin(origin);

        Notification savedWithRef = new Notification();
        savedWithRef.setId(generatedId);
        savedWithRef.setOrigin(origin);
        savedWithRef.setReferenceNumber("DRAFT.IMP." + LocalDate.now().getYear() + "." + generatedId);

        when(notificationRepository.save(any(Notification.class)))
            .thenReturn(savedWithId)
            .thenReturn(savedWithRef);

        // When
        Notification result = notificationService.saveOriginOfImport(notificationDto);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).endsWith(generatedId);
    }

    @Test
    void saveOriginOfImport_shouldIncludeCurrentYearInReferenceNumber() {
        // Given
        Origin origin = new Origin("IT", "false", "REF999");
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(origin)
            .build();

        String generatedId = "507f1f77bcf86cd799439999";
        Notification savedWithId = new Notification();
        savedWithId.setId(generatedId);
        savedWithId.setOrigin(origin);

        int currentYear = LocalDate.now().getYear();
        Notification savedWithRef = new Notification();
        savedWithRef.setId(generatedId);
        savedWithRef.setOrigin(origin);
        savedWithRef.setReferenceNumber("DRAFT.IMP." + currentYear + "." + generatedId);

        when(notificationRepository.save(any(Notification.class)))
            .thenReturn(savedWithId)
            .thenReturn(savedWithRef);

        // When
        Notification result = notificationService.saveOriginOfImport(notificationDto);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).contains(String.valueOf(currentYear));
    }

    @Test
    void findAll_shouldReturnEmptyList() {
        // Given
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        // When
        List<Notification> result = notificationService.findAll();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
        verify(notificationRepository, times(1)).findAll();
    }

    @Test
    void findAll_shouldReturnSingleNotification() {
        // Given
        Origin origin = new Origin("GB", "true", "REF-001");
        Notification notification = new Notification();
        notification.setId("507f1f77bcf86cd799439011");
        notification.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439011");
        notification.setOrigin(origin);
        notification.setCommodity("Live cattle");

        when(notificationRepository.findAll()).thenReturn(Collections.singletonList(notification));

        // When
        List<Notification> result = notificationService.findAll();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(result.get(0).getReferenceNumber()).isEqualTo("DRAFT.IMP.2026.507f1f77bcf86cd799439011");
        assertThat(result.get(0).getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(result.get(0).getCommodity()).isEqualTo("Live cattle");
        verify(notificationRepository, times(1)).findAll();
    }

    @Test
    void findAll_shouldReturnMultipleNotifications() {
        // Given
        Origin origin1 = new Origin("GB", "true", "REF-001");
        Notification notification1 = new Notification();
        notification1.setId("507f1f77bcf86cd799439011");
        notification1.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439011");
        notification1.setOrigin(origin1);
        notification1.setCommodity("Live cattle");

        Origin origin2 = new Origin("FR", "false", "REF-002");
        Notification notification2 = new Notification();
        notification2.setId("507f1f77bcf86cd799439012");
        notification2.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439012");
        notification2.setOrigin(origin2);
        notification2.setCommodity("Live sheep");

        Origin origin3 = new Origin("IE", "true", "REF-003");
        Notification notification3 = new Notification();
        notification3.setId("507f1f77bcf86cd799439013");
        notification3.setReferenceNumber("DRAFT.IMP.2026.507f1f77bcf86cd799439013");
        notification3.setOrigin(origin3);
        notification3.setCommodity("Live pigs");

        List<Notification> notifications = Arrays.asList(notification1, notification2, notification3);
        when(notificationRepository.findAll()).thenReturn(notifications);

        // When
        List<Notification> result = notificationService.findAll();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(result.get(1).getOrigin().getCountryCode()).isEqualTo("FR");
        assertThat(result.get(2).getOrigin().getCountryCode()).isEqualTo("IE");
        assertThat(result).extracting(Notification::getCommodity)
            .containsExactly("Live cattle", "Live sheep", "Live pigs");
        verify(notificationRepository, times(1)).findAll();
    }

    @Test
    void deleteByReferenceNumbers_shouldDeleteAll_whenAllFound() {
        // Given
        String ref1 = "DRAFT.IMP.2026.111";
        String ref2 = "DRAFT.IMP.2026.222";
        Notification n1 = Notification.builder().id("111").referenceNumber(ref1).build();
        Notification n2 = Notification.builder().id("222").referenceNumber(ref2).build();
        HttpHeaders headers = headersWithAuditFields();

        when(notificationRepository.findAllByReferenceNumberIn(List.of(ref1, ref2)))
            .thenReturn(List.of(n1, n2));
        when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

        // When
        notificationService.deleteByReferenceNumbers(List.of(ref1, ref2), headers);

        // Then — deleteAll is called with the found notifications
        verify(notificationRepository).deleteAll(List.of(n1, n2));

        // And an audit record is saved with SUCCESS
        ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
        verify(auditRepository).save(auditCaptor.capture());
        Audit saved = auditCaptor.getValue();
        assertThat(saved.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(saved.getNotificationReferenceNumbers()).containsExactly(ref1, ref2);
        assertThat(saved.getNumberOfNotifications()).isEqualTo(2);
        assertThat(saved.getTraceId()).isEqualTo("test-trace-id");
        assertThat(saved.getUserId()).isEqualTo("test-user-id");
    }

    @Test
    void deleteByReferenceNumbers_shouldThrowNotFoundException_whenOneIsMissing() {
        // Given
        String existingRef = "DRAFT.IMP.2026.111";
        String missingRef  = "DRAFT.IMP.2026.MISSING";
        Notification n1 = Notification.builder().id("111").referenceNumber(existingRef).build();
        HttpHeaders headers = headersWithAuditFields();

        when(notificationRepository.findAllByReferenceNumberIn(List.of(existingRef, missingRef)))
            .thenReturn(List.of(n1));
        when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

        // When / Then
        assertThatThrownBy(() ->
            notificationService.deleteByReferenceNumbers(List.of(existingRef, missingRef), headers))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(missingRef);

        // deleteAll must NOT be called — no partial deletes
        verify(notificationRepository, never()).deleteAll(anyList());

        // But a FAILURE audit record is saved
        ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
        verify(auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo(Result.FAILURE);
    }

    @Test
    void deleteByReferenceNumbers_shouldListAllMissingRefs_inExceptionMessage() {
        // Given
        String missing1 = "DRAFT.IMP.2026.AAA";
        String missing2 = "DRAFT.IMP.2026.BBB";
        HttpHeaders headers = headersWithAuditFields();

        when(notificationRepository.findAllByReferenceNumberIn(List.of(missing1, missing2)))
            .thenReturn(Collections.emptyList());
        when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

        // When / Then
        assertThatThrownBy(() ->
            notificationService.deleteByReferenceNumbers(List.of(missing1, missing2), headers))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(missing1)
            .hasMessageContaining(missing2);

        verify(notificationRepository, never()).deleteAll(anyList());
        verify(auditRepository).save(any(Audit.class));
    }

    @Test
    void deleteByReferenceNumbers_shouldDoNothing_whenListIsEmpty() {
        // When — empty list is passed (defensive guard; controller rejects this before reaching service)
        notificationService.deleteByReferenceNumbers(Collections.emptyList(), new HttpHeaders());

        // Then — repository is never called
        verify(notificationRepository, never()).findAllByReferenceNumberIn(anyList());
        verify(notificationRepository, never()).deleteAll(anyList());
        verify(auditRepository, never()).save(any(Audit.class));
    }
}
