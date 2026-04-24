package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;

import java.time.LocalDate;
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
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

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
    void shouldCreateNewNotificationWithGeneratedReferenceNumber() {
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
    void shouldUpdateExistingNotification() {
        // Given - existing notification with ID and reference number
        String existingId = "507f191e810c19729de860ea";
        String referenceNumber = "DRAFT.IMP.2026." + existingId;
        Origin origin = new Origin("FR", "false", "REF456");
        AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
        Species species = species();
        CommodityComplement complement = new CommodityComplement("LIVE", 5, null, List.of(species));
        Commodity commodity = Commodity.builder()
            .name("Fish")
            .commodityComplement(List.of(complement))
            .build();
        String cphNumber = "123456789";
        
        Transport transport = Transport.builder()
            .portOfEntry("ABERDEEN")
            .arrivalDate(LocalDate.of(2026, 1, 1))
            .build();

        Notification existingNotification = new Notification();
        existingNotification.setId(existingId);
        existingNotification.setReferenceNumber(referenceNumber);
        existingNotification.setOrigin(origin);

        when(notificationRepository.findByReferenceNumber(referenceNumber))
            .thenReturn(Optional.of(existingNotification));

        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(origin)
            .commodity(commodity)
            .consignor(consignors().getFirst())
            .destination(destinations().getFirst())
            .additionalDetails(additionalDetails)
            .reasonForImport("PERMANENT")
            .cphNumber(cphNumber)
            .transport(transport)
            .build();

        Notification updatedNotification = Notification.builder()
            .id(existingId)
            .referenceNumber(referenceNumber)
            .origin(origin)
            .commodity(commodity)
            .consignor(consignors().getFirst())
            .destination(destinations().getFirst())
            .additionalDetails(additionalDetails)
            .reasonForImport("PERMANENT")
            .cphNumber(cphNumber)
            .transport(transport)
            .build();

        when(notificationRepository.save(any(Notification.class))).thenReturn(updatedNotification);

        // When
        Notification result = notificationService.saveOriginOfImport(updateDto);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isEqualTo("DRAFT.IMP.2026." + existingId);
        assertThat(result.getId()).isEqualTo(existingId);
        assertThat(result.getOrigin()).isEqualTo(origin);
        assertThat(result.getCommodity().getName()).isEqualTo("Fish");
        assertThat(result.getCommodity().getCommodityComplement()).hasSize(1);
        assertThat(result.getCommodity().getCommodityComplement().getFirst().getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(result.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst().getValue()).isEqualTo("BOV");
        assertThat(result.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst().getEarTag()).isEqualTo("UK01234567890");
        assertThat(result.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst().getPassport()).isEqualTo("UK0123456700999");
        assertThat(result.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(result.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
        assertThat(result.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(result.getConsignor().getName()).isEqualTo("Astra Rosales");
        assertThat(result.getConsignor().getAddress().getAddressLine1()).isEqualTo("43 East Hague Extension");
        assertThat(result.getConsignor().getAddress().getCountry()).isEqualTo("Switzerland");
        assertThat(result.getDestination().getName()).isEqualTo("United Commerce");
        assertThat(result.getDestination().getAddress().getAddressLine1()).isEqualTo("446 Church Lane");
        assertThat(result.getDestination().getAddress().getCountry()).isEqualTo("United Kingdom");
        assertThat(result.getCphNumber()).isEqualTo("123456789");
        assertThat(result.getTransport()).isEqualTo(transport);
        verify(notificationRepository, times(1)).save(any(Notification.class));
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
    void findAllReferenceNumbers_shouldReturnEmptyList_whenNoNotificationsExist() {
        // Given
        when(notificationRepository.findAllProjectedBy()).thenReturn(Collections.emptyList());

        // When
        List<String> result = notificationService.findAllReferenceNumbers();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
        verify(notificationRepository, times(1)).findAllProjectedBy();
    }

    @Test
    void findAllReferenceNumbers_shouldReturnReferenceNumbers_whenNotificationsExist() {
        // Given
        NotificationReferenceOnly ref1 = () -> "DRAFT.IMP.2026.abc123";
        NotificationReferenceOnly ref2 = () -> "DRAFT.IMP.2026.xyz456";
        when(notificationRepository.findAllProjectedBy()).thenReturn(List.of(ref1, ref2));

        // When
        List<String> result = notificationService.findAllReferenceNumbers();

        // Then
        assertThat(result).containsExactly("DRAFT.IMP.2026.abc123", "DRAFT.IMP.2026.xyz456");
        verify(notificationRepository, times(1)).findAllProjectedBy();
    }

    @Test
    void deleteByReferenceNumbers_shouldDeleteAll_whenAllFound() {
        // Given
        String ref1 = "DRAFT.IMP.2026.111";
        String ref2 = "DRAFT.IMP.2026.222";
        NotificationReferenceOnly n1 = () -> ref1;
        NotificationReferenceOnly n2 = () -> ref2;
        HttpHeaders headers = headersWithAuditFields();

        when(notificationRepository.findAllByReferenceNumberIn(List.of(ref1, ref2)))
            .thenReturn(List.of(n1, n2));
        when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

        // When
        notificationService.deleteByReferenceNumbers(List.of(ref1, ref2), headers);

        // Then — deleteAllByReferenceNumberIn is called with the original reference numbers
        verify(notificationRepository).deleteAllByReferenceNumberIn(List.of(ref1, ref2));

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
        NotificationReferenceOnly n1 = () -> existingRef;
        HttpHeaders headers = headersWithAuditFields();

        when(notificationRepository.findAllByReferenceNumberIn(List.of(existingRef, missingRef)))
            .thenReturn(List.of(n1));
        when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

        // When / Then
        assertThatThrownBy(() ->
            notificationService.deleteByReferenceNumbers(List.of(existingRef, missingRef), headers))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(missingRef);

        // deleteAllByReferenceNumberIn must NOT be called — no partial deletes
        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());

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

        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        verify(auditRepository).save(any(Audit.class));
    }

    @Test
    void deleteByReferenceNumbers_shouldDoNothing_whenListIsEmpty() {
        // When — empty list is passed (defensive guard; controller rejects this before reaching service)
        notificationService.deleteByReferenceNumbers(Collections.emptyList(), new HttpHeaders());

        // Then — repository is never called
        verify(notificationRepository, never()).findAllByReferenceNumberIn(anyList());
        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        verify(auditRepository, never()).save(any(Audit.class));
    }
}
