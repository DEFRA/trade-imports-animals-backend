package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.AMEND;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.DRAFT;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.SUBMITTED;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.transporters;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort.Direction;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String TEST_TRACE_ID = "test-trace-id";
    private static final String TEST_USER_ID = "test-user-id";

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private LockProvider lockProvider;

    @Mock
    private ReferenceNumberGenerator referenceNumberGenerator;

    private NotificationService notificationService;

    private final NotificationMapper notificationMapper = Mappers.getMapper(
        NotificationMapper.class);

    @BeforeEach
    void setUp() {
        LockingTaskExecutor lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        notificationService = new NotificationService(notificationRepository, auditRepository,
            documentService, outboxService, lockingTaskExecutor,
            notificationMapper, new NotificationCopyMapper(), referenceNumberGenerator, Duration.ZERO, 54, 50);
    }

    @Nested
    class SaveOriginOfImport {

        @Test
        void saveOriginOfImport_shouldCreateNotificationWithGeneratedReferenceNumber() {
            // Given - new notification without referenceNumber
            Origin origin = new Origin("GB", "true", "REF123");
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .build();

            String generatedId = "507f1f77bcf86cd799439011";
            String expectedRef = "GBN-AG-26-ABC123";

            Notification saved = new Notification();
            saved.setId(generatedId);
            saved.setOrigin(origin);
            saved.setReferenceNumber(expectedRef);

            when(referenceNumberGenerator.generate()).thenReturn(expectedRef);
            when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

            // When
            Notification result = notificationService.saveOriginOfImport(notificationDto);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(expectedRef);
            assertThat(result.getId()).isEqualTo(generatedId);
            assertThat(result.getOrigin()).isEqualTo(origin);
            verify(referenceNumberGenerator).generate();
            verify(notificationRepository, times(1)).save(any(Notification.class));
        }

        @Test
        void saveOriginOfImport_shouldRetryPersistence_whenDuplicateKeyExceptionOnFirstAttempt() {
            // Given — first persistence attempt collides, second succeeds
            Origin origin = new Origin("GB", "true", "REF123");
            NotificationDto notificationDto = NotificationDto.builder().origin(origin).build();

            Notification saved = new Notification();
            saved.setId("507f1f77bcf86cd799439011");
            saved.setOrigin(origin);
            saved.setReferenceNumber("GBN-AG-26-ABC002");

            when(referenceNumberGenerator.generate())
                .thenReturn("GBN-AG-26-ABC001")  // first attempt — collides
                .thenReturn("GBN-AG-26-ABC002"); // second attempt — succeeds

            when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenReturn(saved);

            // When
            Notification result = notificationService.saveOriginOfImport(notificationDto);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo("GBN-AG-26-ABC002");
            verify(referenceNumberGenerator, times(2)).generate();
            verify(notificationRepository, times(2)).save(any(Notification.class));
        }

        @Test
        void saveOriginOfImport_shouldThrowIllegalStateException_whenAllPersistenceRetriesExhausted() {
            // Given — all three persistence attempts collide
            Origin origin = new Origin("GB", "true", "REF123");
            NotificationDto notificationDto = NotificationDto.builder().origin(origin).build();

            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-ABC001");
            when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenThrow(new DuplicateKeyException("duplicate reference number"));

            // When / Then
            assertThatThrownBy(() -> notificationService.saveOriginOfImport(notificationDto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");

            verify(referenceNumberGenerator, times(3)).generate();
            verify(notificationRepository, times(3)).save(any(Notification.class));
        }

        @Test
        void saveOriginOfImport_shouldUpdateExistingNotification() {
            // Given - existing notification with ID and reference number
            String existingId = "507f191e810c19729de860ea";
            String referenceNumber = "GBN-AG-26-507F19";
            Origin origin = new Origin("FR", "false", "REF456");
            AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION",
                "true");
            Species species = species();
            CommodityComplement complement = new CommodityComplement("LIVE", 5, null,
                List.of(species));
            Commodity commodity = Commodity.builder()
                .name("Fish")
                .commodityComplement(List.of(complement))
                .build();
            String cphNumber = "123456789";

            Transport transport = Transport.builder()
                .portOfEntry("ABERDEEN")
                .arrivalDate(LocalDate.of(2026, 1, 1))
                .transporter(transporters().getFirst())
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
                .consignment(consignments().getFirst())
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
                .consignment(consignments().getFirst())
                .build();

            when(notificationRepository.save(any(Notification.class))).thenReturn(
                updatedNotification);

            // When
            Notification result = notificationService.saveOriginOfImport(updateDto);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getReferenceNumber()).isEqualTo("GBN-AG-26-507F19");
            assertThat(result.getId()).isEqualTo(existingId);
            assertThat(result.getOrigin()).isEqualTo(origin);
            assertThat(result.getCommodity().getName()).isEqualTo("Fish");
            assertThat(result.getCommodity().getCommodityComplement()).hasSize(1);
            assertThat(result.getCommodity().getCommodityComplement().getFirst()
                .getTypeOfCommodity()).isEqualTo("LIVE");
            assertThat(
                result.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
                    .getValue()).isEqualTo("BOV");
            assertThat(
                result.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
                    .getEarTag()).isEqualTo("UK01234567890");
            assertThat(
                result.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
                    .getPassport()).isEqualTo("UK0123456700999");
            assertThat(result.getAdditionalDetails().getCertifiedFor()).isEqualTo(
                "HUMAN_CONSUMPTION");
            assertThat(result.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
            assertThat(result.getReasonForImport()).isEqualTo("PERMANENT");
            assertThat(result.getConsignor().getName()).isEqualTo("Astra Rosales");
            assertThat(result.getConsignor().getAddress().getAddressLine1()).isEqualTo(
                "43 East Hague Extension");
            assertThat(result.getConsignor().getAddress().getCountry()).isEqualTo("Switzerland");
            assertThat(result.getDestination().getName()).isEqualTo("United Commerce");
            assertThat(result.getDestination().getAddress().getAddressLine1()).isEqualTo(
                "446 Church Lane");
            assertThat(result.getDestination().getAddress().getCountry()).isEqualTo(
                "United Kingdom");
            assertThat(result.getCphNumber()).isEqualTo("123456789");
            assertThat(result.getTransport()).isEqualTo(transport);
            assertThat(result.getConsignment().getName())
                .isEqualTo("Animal and Plant Health Agency");
            assertThat(result.getConsignment().getAddress().getAddressLine1())
                .isEqualTo("Woodham Lane");
            assertThat(result.getConsignment().getAddress().getCountry())
                .isEqualTo("United Kingdom");
            verify(notificationRepository, times(1)).save(any(Notification.class));
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_shouldReturnEmptyPage() {
            // Given
            Page<Notification> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            NotificationPageResponse result = notificationService.findAll(1, null);

            // Then
            assertThat(result).isNotNull();
            verify(notificationRepository, times(1))
                .findAllByStatusIn(eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class));
        }

        @Test
        void findAll_shouldExcludeDeletedNotifications() {
            // Given — only DRAFT and SUBMITTED are passed as the allowlist; DELETED is excluded
            Notification draft = Notification.builder()
                .referenceNumber("GBN-AG-26-000DFT")
                .status(DRAFT)
                .build();
            Notification submitted = Notification.builder()
                .referenceNumber("GBN-AG-26-000SUB")
                .status(SUBMITTED)
                .build();

            Page<Notification> page = new PageImpl<>(
                List.of(draft, submitted), PageRequest.of(0, 54), 0);

            when(notificationRepository.findAllByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            // When
            NotificationPageResponse result = notificationService.findAll(1, null);

            // Then — only DRAFT and SUBMITTED are returned
            assertThat(result.content()).hasSize(2);
            assertThat(result.content()).extracting(NotificationDto::getStatus)
                .containsExactlyInAnyOrder(DRAFT, SUBMITTED);
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(54);
            verify(notificationRepository, times(1))
                .findAllByStatusIn(eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class));
        }

        @Test
        void findAll_shouldMapStatusToNotificationDto() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .origin(new Origin("GB", "true", "REF-1"))
                .status(SUBMITTED)
                .build();
            Page<Notification> page = new PageImpl<>(List.of(notification), PageRequest.of(0, 54),
                1);
            when(notificationRepository.findAllByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            // When
            NotificationPageResponse result = notificationService.findAll(1, null);

            // Then
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().getReferenceNumber()).isEqualTo(
                "GBN-AG-26-ABC123");
            assertThat(result.content().getFirst().getStatus()).isEqualTo(
                SUBMITTED);
        }

        @Test
        void findAll_shouldIncludeAmendNotifications() {
            // Regression: notifications in AMEND were silently excluded from the
            // dashboard before AMEND was added to the allow-list (EUDPA-171).
            Notification amend = Notification.builder()
                .referenceNumber("GBN-AG-26-000AMD")
                .status(AMEND)
                .build();

            Page<Notification> page = new PageImpl<>(
                List.of(amend), PageRequest.of(0, 54), 0);

            when(notificationRepository.findAllByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            NotificationPageResponse result = notificationService.findAll(1, null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content()).extracting(NotificationDto::getStatus)
                .containsExactly(AMEND);
        }

        @Test
        void findAll_shouldUseCreatedAtSort_whenRequested() {
            Page<Notification> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(emptyPage);

            notificationService.findAll(1, "createdAt,asc");

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getSort().getOrderFor("created").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        }
    }

    @Nested
    class FindAllReferenceNumbers {

        @Test
        void findAllReferenceNumbers_shouldReturnEmptyPage_whenNoNotificationsExist() {
            // Given
            Page<NotificationReferenceOnly> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllProjectedBy(any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            ReferenceNumberPageResponse result = notificationService.findAllReferenceNumbers(0);

            // Then
            assertThat(result.content()).isEmpty();
            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(54);
            assertThat(result.numberOfElements()).isZero();
            assertThat(result.totalElements()).isZero();
            assertThat(result.totalPages()).isZero();
            verify(notificationRepository, times(1)).findAllProjectedBy(any(Pageable.class));
        }

        @Test
        void findAllReferenceNumbers_shouldReturnPagedReferenceNumbers_whenNotificationsExist() {
            // Given
            NotificationReferenceOnly ref1 = () -> "GBN-AG-26-ABC123";
            NotificationReferenceOnly ref2 = () -> "GBN-AG-26-XYZ456";
            Page<NotificationReferenceOnly> page = new PageImpl<>(
                List.of(ref1, ref2), PageRequest.of(0, 54), 2);
            when(notificationRepository.findAllProjectedBy(any(Pageable.class))).thenReturn(page);

            // When
            ReferenceNumberPageResponse result = notificationService.findAllReferenceNumbers(0);

            // Then
            assertThat(result.content()).containsExactly("GBN-AG-26-ABC123", "GBN-AG-26-XYZ456");
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.totalPages()).isEqualTo(1);
            verify(notificationRepository, times(1)).findAllProjectedBy(any(Pageable.class));
        }

        @Test
        void findAllReferenceNumbers_shouldPassPageRequestWithConfiguredPageSizeAndAscendingIdSort() {
            // Given
            Page<NotificationReferenceOnly> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(2, 54), 200);
            when(notificationRepository.findAllProjectedBy(any(Pageable.class)))
                .thenReturn(emptyPage);

            // When
            notificationService.findAllReferenceNumbers(2);

            // Then
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllProjectedBy(pageableCaptor.capture());
            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(50);
            assertThat(pageable.getSort().getOrderFor("created")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("created").getDirection())
                .isEqualTo(Direction.DESC);
        }
    }

    @Nested
    class DeleteByReferenceNumbers {

        @Test
        void deleteByReferenceNumbers_shouldDeleteAll_whenAllFound() {
            // Given
            String ref1 = "GBN-AG-26-000111";
            String ref2 = "GBN-AG-26-000222";
            NotificationReferenceOnly n1 = () -> ref1;
            NotificationReferenceOnly n2 = () -> ref2;

            when(notificationRepository.findAllByReferenceNumberIn(List.of(ref1, ref2)))
                .thenReturn(List.of(n1, n2));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When
            notificationService.deleteByReferenceNumbers(List.of(ref1, ref2),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            // Then — deleteAllByReferenceNumberIn is called with the original reference numbers
            verify(notificationRepository).deleteAllByReferenceNumberIn(List.of(ref1, ref2));

            // And cascade document deletion is triggered for the same refs
            verify(documentService).deleteForNotificationRefs(List.of(ref1, ref2));

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
            String existingRef = "GBN-AG-26-000111";
            String missingRef = "GBN-AG-26-MSNG00";
            NotificationReferenceOnly n1 = () -> existingRef;

            when(
                notificationRepository.findAllByReferenceNumberIn(List.of(existingRef, missingRef)))
                .thenReturn(List.of(n1));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When / Then
            assertThatThrownBy(() ->
                notificationService.deleteByReferenceNumbers(List.of(existingRef, missingRef),
                    new AuditContext(TEST_TRACE_ID, TEST_USER_ID)))
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
            String missing1 = "GBN-AG-26-000AAA";
            String missing2 = "GBN-AG-26-000BBB";

            when(notificationRepository.findAllByReferenceNumberIn(List.of(missing1, missing2)))
                .thenReturn(Collections.emptyList());
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When / Then
            assertThatThrownBy(() ->
                notificationService.deleteByReferenceNumbers(List.of(missing1, missing2),
                    new AuditContext(TEST_TRACE_ID, TEST_USER_ID)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing1)
                .hasMessageContaining(missing2);

            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(auditRepository).save(any(Audit.class));
        }

        @Test
        void deleteByReferenceNumbers_shouldDoNothing_whenListIsEmpty() {
            // When — empty list is passed (defensive guard; controller rejects this before reaching service)
            notificationService.deleteByReferenceNumbers(Collections.emptyList(),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            // Then — repository is never called
            verify(notificationRepository, never()).findAllByReferenceNumberIn(anyList());
            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(auditRepository, never()).save(any(Audit.class));
        }

        @Test
        void deleteByReferenceNumbers_shouldCascadeDeleteDocuments_whenNotificationsDeleted() {
            // Given
            String referenceNumber = "GBN-AG-26-000111";
            NotificationReferenceOnly notificationRef = () -> referenceNumber;

            when(notificationRepository.findAllByReferenceNumberIn(List.of(referenceNumber)))
                .thenReturn(List.of(notificationRef));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            // When
            notificationService.deleteByReferenceNumbers(List.of(referenceNumber),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            // Then — notifications deleted first, then documents cascade deleted (order matters)
            InOrder inOrder = inOrder(notificationRepository, documentService);
            inOrder.verify(notificationRepository)
                .deleteAllByReferenceNumberIn(List.of(referenceNumber));
            inOrder.verify(documentService).deleteForNotificationRefs(List.of(referenceNumber));
        }
    }

    @Nested
    class SubmitNotification {

        @BeforeEach
        void setUp() {
            // Default: lock is acquired and the task executes
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void submitNotification_shouldSetStatusToSubmittedAndSave() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            Notification notification = Notification.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            Notification result = notificationService.submitNotification(referenceNumber,
                "trace-001");

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notification);
            verify(outboxService).appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001");
        }

        @Test
        void submitNotification_shouldWriteOutboxEvent_afterSavingNotification() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            Notification notification = Notification.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.submitNotification(referenceNumber, "trace-001");

            // Then — save must happen before the outbox event is written
            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(notification);
            inOrder.verify(outboxService).appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001");
        }

        @Test
        void submitNotification_shouldThrowOutboxWriteException_whenAppendEventFails() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            Notification notification = Notification.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new OutboxWriteException("Forced failure", "agg-id", 1L, "trace-001"))
                .when(outboxService).appendEvent(any(), any(), any());

            // When / Then — exception propagates out of submitNotification
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-001"))
                .isInstanceOf(OutboxWriteException.class);
        }

        @Test
        void submitNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-001"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any());
            // Lock must never be acquired — the notification lookup fails before the lock scope
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void submitNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            Notification notification = Notification.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            // Lock not available — executor returns wasExecuted() == false
            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-001"))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void submitNotification_shouldSetStatusToSubmittedAndSave_whenAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-AMEND1";
            Notification notification = Notification.builder()
                .id("notif-id-amend-1")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            Notification result = notificationService.submitNotification(referenceNumber,
                "trace-002");

            // Then
            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notification);
            verify(outboxService).appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-002");
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenAlreadySubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-ALREADY";
            Notification notification = Notification.builder()
                .id("notif-id-already")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-003"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenDeleted() {
            // Given
            String referenceNumber = "GBN-AG-26-DELETED";
            Notification notification = Notification.builder()
                .id("notif-id-deleted")
                .referenceNumber(referenceNumber)
                .status(NotificationStatus.DELETED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, "trace-004"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any());
        }
    }

    @Nested
    class AmendNotification {

        @BeforeEach
        void setUp() {
            // Default: lock is acquired and the task executes
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(mock(SimpleLock.class)));
        }

        @Test
        void amendNotification_shouldSetStatusToAmendAndSave_whenSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD001";
            Notification notification = Notification.builder()
                .id("notif-id-amd-1")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            Notification result = notificationService.amendNotification(referenceNumber,
                "trace-amd-1");

            // Then
            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notification);
            verify(outboxService).appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-1");
        }

        @Test
        void amendNotification_shouldWriteOutboxEvent_afterSavingNotification() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD002";
            Notification notification = Notification.builder()
                .id("notif-id-amd-2")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.amendNotification(referenceNumber, "trace-amd-2");

            // Then — save must happen before the outbox event is written
            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(notification);
            inOrder.verify(outboxService).appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-2");
        }

        @Test
        void amendNotification_shouldThrowBadRequest_whenDraft() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD003";
            Notification notification = Notification.builder()
                .id("notif-id-amd-3")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-3"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DRAFT");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void amendNotification_shouldThrowBadRequest_whenAlreadyAmend() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD004";
            Notification notification = Notification.builder()
                .id("notif-id-amd-4")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-4"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("AMEND");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any());
        }

        @Test
        void amendNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-5"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void amendNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD006";
            Notification notification = Notification.builder()
                .id("notif-id-amd-6")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-6"))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-amd-6");
                });

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void amendNotification_shouldThrowOutboxWriteException_whenAppendEventFails() {
            // Given
            String referenceNumber = "GBN-AG-26-AMD007";
            Notification notification = Notification.builder()
                .id("notif-id-amd-7")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new OutboxWriteException("Forced failure", "agg-id", 1L, "trace-amd-7"))
                .when(outboxService).appendEvent(any(), any(), any());

            // When / Then
            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-7"))
                .isInstanceOf(OutboxWriteException.class);
        }
    }

    @Nested
    class FindByRef {

        @Test
        void findByRef_shouldReturnHydratedNotification_withDocuments() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            Origin origin = new Origin("GB", "true", "REF-001");
            Notification notification = Notification.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .origin(origin)
                .commodity(Commodity.builder().name("Live bovine animals").build())
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .consignment(consignments().getFirst())
                .build();

            AccompanyingDocument document = AccompanyingDocument.builder()
                .id("doc-id-001")
                .notificationReferenceNumber(referenceNumber)
                .uploadId("upload-abc-123")
                .documentType(DocumentType.ITAHC)
                .documentReference("UKGB2026001")
                .scanStatus(ScanStatus.COMPLETE)
                .files(Collections.emptyList())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(documentService.findByNotificationRef(referenceNumber))
                .thenReturn(List.of(document));

            // When
            NotificationResponse response = notificationService.findByRef(referenceNumber);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.referenceNumber()).isEqualTo(referenceNumber);
            assertThat(response.origin().getCountryCode()).isEqualTo("GB");
            assertThat(response.commodity().getName()).isEqualTo("Live bovine animals");
            assertThat(response.consignor().getName()).isEqualTo(consignors().getFirst().getName());
            assertThat(response.destination().getName()).isEqualTo(
                destinations().getFirst().getName());
            assertThat(response.consignment()).isEqualTo(consignments().getFirst());
            assertThat(response.accompanyingDocuments()).hasSize(1);
            assertThat(response.accompanyingDocuments().getFirst().uploadId()).isEqualTo(
                "upload-abc-123");
            assertThat(response.accompanyingDocuments().getFirst().scanStatus()).isEqualTo(
                ScanStatus.COMPLETE);
        }

        @Test
        void findByRef_shouldReturnNotificationWithEmptyDocuments_whenNoneUploaded() {
            // Given
            String referenceNumber = "GBN-AG-26-XYZ456";
            Notification notification = Notification.builder()
                .id("notif-id-002")
                .referenceNumber(referenceNumber)
                .origin(new Origin("IE", "false", null))
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(documentService.findByNotificationRef(referenceNumber))
                .thenReturn(Collections.emptyList());

            // When
            NotificationResponse response = notificationService.findByRef(referenceNumber);

            // Then
            assertThat(response.referenceNumber()).isEqualTo(referenceNumber);
            assertThat(response.accompanyingDocuments()).isEmpty();
        }

        @Test
        void findByRef_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.findByRef(referenceNumber))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(documentService, never()).findByNotificationRef(any());
        }
    }

    @Nested
    class CopyNotification {

        @Test
        void copyNotification_shouldCreateNewDraftWithNewReferenceNumber() {
            // Given
            String sourceRef = "GBN-AG-26-SRC001";
            String newRef = "GBN-AG-26-NEW001";
            Notification source = Notification.builder()
                .referenceNumber(sourceRef)
                .origin(new Origin("IE", "no", "INT-REF-DO-NOT-COPY"))
                .status(NotificationStatus.DRAFT)
                .build();

            Notification created = Notification.builder()
                .referenceNumber(newRef)
                .status(NotificationStatus.DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(Notification.class))).thenReturn(created);

            // When
            Notification result = notificationService.copyNotification(sourceRef);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void copyNotification_shouldCreateNewDraftFromSubmittedSource() {
            // Given
            String sourceRef = "GBN-AG-26-SUB001";
            String newRef = "GBN-AG-26-NEW002";
            Notification source = Notification.builder()
                .referenceNumber(sourceRef)
                .origin(new Origin("IE", "no", "INT-REF-DO-NOT-COPY"))
                .status(NotificationStatus.SUBMITTED)
                .build();

            Notification created = Notification.builder()
                .referenceNumber(newRef)
                .status(NotificationStatus.DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(Notification.class))).thenReturn(created);

            // When
            Notification result = notificationService.copyNotification(sourceRef);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void copyNotification_shouldRetainCopiedFields() {
            // Given
            String sourceRef = "GBN-AG-26-SRC002";
            Origin origin = new Origin("DE", "yes", "INTERNAL-REF");
            AdditionalDetails additionalDetails = new AdditionalDetails("Breeding", "yes");
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5,
                List.of(species()));
            Commodity commodity = Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build();

            Notification source = Notification.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .origin(origin)
                .commodity(commodity)
                .reasonForImport("internalMarket")
                .additionalDetails(additionalDetails)
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .cphNumber("12/345/6789")
                .transport(Transport.builder()
                    .portOfEntry("GBDVR")
                    .arrivalDate(LocalDate.of(2026, 5, 1))
                    .transporter(transporters().getFirst())
                    .build())
                .consignment(consignments().getFirst())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-CPY002");
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.copyNotification(sourceRef);

            // Then — capture what was saved and assert retained fields
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            Notification saved = captor.getValue();

            // Retained
            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.DRAFT);
            assertThat(saved.getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(saved.getOrigin().getRequiresRegionCode()).isEqualTo("yes");
            assertThat(saved.getReasonForImport()).isEqualTo("internalMarket");
            assertThat(saved.getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(saved.getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
            assertThat(saved.getConsignor()).isEqualTo(consignors().getFirst());
            assertThat(saved.getDestination()).isEqualTo(destinations().getFirst());
            assertThat(saved.getCphNumber()).isEqualTo("12/345/6789");
        }

        @Test
        void copyNotification_shouldResetExcludedFields() {
            // Given
            String sourceRef = "GBN-AG-26-SRC003";
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5,
                List.of(species()));
            Notification source = Notification.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .origin(new Origin("FR", "no", "DO-NOT-COPY"))
                .commodity(Commodity.builder()
                    .name("Cattle")
                    .commodityComplement(List.of(complement))
                    .build())
                .additionalDetails(new AdditionalDetails("Slaughter", "no"))
                .transport(Transport.builder()
                    .portOfEntry("GBFXT")
                    .arrivalDate(LocalDate.of(2026, 6, 1))
                    .build())
                .consignment(consignments().getFirst())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-CPY003");
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            notificationService.copyNotification(sourceRef);

            // Then — excluded fields must be null on the saved copy
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            Notification saved = captor.getValue();

            assertThat(saved.getOrigin().getInternalReference()).isNull();
            assertThat(saved.getAdditionalDetails().getUnweanedAnimals()).isNull();
            assertThat(saved.getTransport()).isNull();
            assertThat(saved.getConsignment()).isNull();
            // Commodity complement strips per-animal data
            CommodityComplement cc = saved.getCommodity().getCommodityComplement().getFirst();
            assertThat(cc.getTypeOfCommodity()).isEqualTo("LIVE");
            assertThat(cc.getTotalNoOfAnimals()).isNull();
            assertThat(cc.getTotalNoOfPackages()).isNull();
            assertThat(cc.getSpecies()).isNull();
        }

        @Test
        void copyNotification_shouldCreateNewDraftFromAmendSource() {
            // Regression: AMEND notifications were rejected by copyNotification's
            // status guard before AMEND was added to the allow-list (EUDPA-171).
            String sourceRef = "GBN-AG-26-AMD001";
            String newRef = "GBN-AG-26-NEW-AMD";
            Notification source = Notification.builder()
                .referenceNumber(sourceRef)
                .origin(new Origin("IE", "no", "INT-REF-DO-NOT-COPY"))
                .status(AMEND)
                .build();

            Notification created = Notification.builder()
                .referenceNumber(newRef)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(Notification.class))).thenReturn(created);

            Notification result = notificationService.copyNotification(sourceRef);

            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(DRAFT);
        }

        @Test
        void copyNotification_shouldThrowNotFoundException_whenSourceNotFound() {
            // Given
            String sourceRef = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(sourceRef);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowBadRequestException_whenSourceIsDeleted() {
            // Given
            String sourceRef = "GBN-AG-26-DELETED";
            Notification deleted = Notification.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DELETED)
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(deleted));

            // When / Then
            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    class SoftDeleteNotification {

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenDraft() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC123";
            Notification notification = Notification.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            Notification result = notificationService.softDeleteNotification(referenceNumber);

            // Then
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notification);
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenSubmitted() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC456";
            Notification notification = Notification.builder()
                .id("notif-id-002")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            Notification result = notificationService.softDeleteNotification(referenceNumber);

            // Then
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notification);
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenAmend() {
            // Regression: AMEND notifications were rejected by softDelete's
            // status guard before AMEND was added to the allow-list (EUDPA-171).
            // The AMEND view page renders a Delete button (AC4) that reaches here.
            String referenceNumber = "GBN-AG-26-ABCAMD";
            Notification notification = Notification.builder()
                .id("notif-id-amend-del")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));
            when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Notification result = notificationService.softDeleteNotification(referenceNumber);

            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notification);
        }

        @Test
        void softDeleteNotification_shouldThrowBadRequestException_whenAlreadyDeleted() {
            // Given
            String referenceNumber = "GBN-AG-26-ABC789";
            Notification notification = Notification.builder()
                .id("notif-id-003")
                .referenceNumber(referenceNumber)
                .status(NotificationStatus.DELETED)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notification));

            // When / Then
            assertThatThrownBy(() -> notificationService.softDeleteNotification(referenceNumber))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void softDeleteNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            // Given
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.softDeleteNotification(referenceNumber))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
        }
    }
}
