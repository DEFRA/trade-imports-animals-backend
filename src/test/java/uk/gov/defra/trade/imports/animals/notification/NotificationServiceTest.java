package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.AMEND;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.DELETED;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.DRAFT;
import static uk.gov.defra.trade.imports.animals.notification.NotificationStatus.SUBMITTED;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.logEventsDuring;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.transporters;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.bson.Document;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort.Direction;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.outbox.Actor;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String TEST_TRACE_ID = "test-trace-id";
    private static final String TEST_USER_ID = "test-user-id";
    private static final String ORG_ID = "5900002";
    private static final Long SUBMIT_TOKEN = 7L;

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

    @InjectMocks
    private DefaultLockingTaskExecutor lockingTaskExecutor;

    @BeforeEach
    void setUp() {
        lenient().when(lockProvider.lock(any()))
            .thenReturn(Optional.of(mock(SimpleLock.class)));
        notificationService = buildService(new NotificationTtlConfig(null, "local"));
    }

    private static ConsignmentParty literalParty() {
        return ConsignmentParty.builder()
            .name("Astra Rosales")
            .email("astra@example.com")
            .phone("+41 22 000 0000")
            .address(Address.builder()
                .addressLine1("43 East Hague Extension")
                .townOrCity("Vernier")
                .postcode("30055")
                .countryCode("CH")
                .build())
            .build();
    }

    private NotificationService buildService(NotificationTtlConfig ttlConfig) {
        return buildService(ttlConfig, Clock.systemUTC());
    }

    private NotificationService buildService(NotificationTtlConfig ttlConfig, Clock clock) {
        return new NotificationService(notificationRepository, auditRepository,
            documentService, outboxService, lockingTaskExecutor,
            new NotificationCopyMapper(),
            Mappers.getMapper(NotificationContentMapper.class),
            referenceNumberGenerator, ttlConfig, clock,
            Duration.ZERO, 54, 50);
    }

    private static List<String> messagesOf(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    class SaveNotification {

        @Test
        void saveNotification_shouldCreateDraftWithGeneratedReferenceNumber() {
            Origin origin = Origin.builder()
                .countryCode("GB")
                .requiresRegionCode("true")
                .internalReference("REF123")
                .build();
            NotificationDto notificationDto = NotificationDto.builder()
                .origin(origin)
                .build();

            String expectedRef = "GBN-AG-26-ABC123";

            when(referenceNumberGenerator.generate()).thenReturn(expectedRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.saveNotification(notificationDto, "", null);

            assertThat(result.getReferenceNumber()).isEqualTo(expectedRef);
            assertThat(result.getStatus()).isEqualTo(DRAFT);
            assertThat(result.getNotification().getOrigin()).isEqualTo(origin);
            verify(referenceNumberGenerator).generate();
            verify(notificationRepository, times(1)).save(any(NotificationAggregate.class));
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_CREATED), any(), any());
        }

        @Test
        void saveNotification_shouldCarryTheStoredConsignorLiteralInCreatedOutbox() {
            String expectedRef = "GBN-AG-26-NEW-REF";
            NotificationDto notificationDto = NotificationDto.builder()
                .consignor(literalParty())
                .build();

            when(referenceNumberGenerator.generate()).thenReturn(expectedRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Actor actor = Actor.builder().organisationId(ORG_ID).build();

            NotificationAggregate saved = notificationService.saveNotification(notificationDto, "trace-new", actor);

            assertThat(saved.getNotification().getConsignor()).isEqualTo(literalParty());

            ArgumentCaptor<NotificationAggregate> outboxCaptor =
                ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                outboxCaptor.capture(), eq(OutboxEventType.NOTIFICATION_CREATED), eq("trace-new"), eq(actor));
            assertThat(outboxCaptor.getValue().getNotification().getConsignor())
                .isEqualTo(literalParty());
        }

        @Test
        void saveNotification_shouldRetryPersistence_whenDuplicateKeyExceptionOnFirstAttempt() {
            Origin origin = Origin.builder()
                .countryCode("GB")
                .requiresRegionCode("true")
                .internalReference("REF123")
                .build();
            NotificationDto notificationDto = NotificationDto.builder().origin(origin).build();

            NotificationAggregate saved = new NotificationAggregate();
            saved.setNotification(new Notification());
            saved.setId("507f1f77bcf86cd799439011");
            saved.getNotification().setOrigin(origin);
            saved.setReferenceNumber("GBN-AG-26-ABC002");

            when(referenceNumberGenerator.generate())
                .thenReturn("GBN-AG-26-ABC001")
                .thenReturn("GBN-AG-26-ABC002");

            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenReturn(saved);

            NotificationAggregate result = notificationService.saveNotification(notificationDto, "", null);

            assertThat(result.getReferenceNumber()).isEqualTo("GBN-AG-26-ABC002");
            verify(referenceNumberGenerator, times(2)).generate();
            verify(notificationRepository, times(2)).save(any(NotificationAggregate.class));
        }

        @Test
        void saveNotification_shouldThrowIllegalStateException_whenAllPersistenceAttemptsCollide() {
            Origin origin = Origin.builder()
                .countryCode("GB")
                .requiresRegionCode("true")
                .internalReference("REF123")
                .build();
            NotificationDto notificationDto = NotificationDto.builder().origin(origin).build();

            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-ABC001");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenThrow(new DuplicateKeyException("duplicate reference number"))
                .thenThrow(new DuplicateKeyException("duplicate reference number"));

            assertThatThrownBy(() -> notificationService.saveNotification(notificationDto, "", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");

            verify(referenceNumberGenerator, times(3)).generate();
            verify(notificationRepository, times(3)).save(any(NotificationAggregate.class));
        }

        private Throwable saveWhoseThreeAttemptsCollide(DuplicateKeyException lastCollision) {
            when(referenceNumberGenerator.generate())
                .thenReturn("GBN-AG-26-ABC001", "GBN-AG-26-ABC002", "GBN-AG-26-ABC003");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenThrow(new DuplicateKeyException("first collision"))
                .thenThrow(new DuplicateKeyException("second collision"))
                .thenThrow(lastCollision);
            NotificationDto dto = NotificationDto.builder().build();

            return catchThrowable(() -> notificationService.saveNotification(dto, "", null));
        }

        @Test
        void saveNotification_shouldLogEachAttemptWithNoPromiseOfARetry_whenAllPersistenceAttemptsCollide() {
            List<ILoggingEvent> events = logEventsDuring(NotificationService.class,
                () -> saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision")));

            assertThat(messagesOf(events)).containsExactly(
                "Reference number GBN-AG-26-ABC001 collided on persistence attempt 1/3",
                "Reference number GBN-AG-26-ABC002 collided on persistence attempt 2/3",
                "Reference number GBN-AG-26-ABC003 collided on persistence attempt 3/3");
        }

        @Test
        void saveNotification_shouldLogTheThrowableOfEachCollision_whenAllPersistenceAttemptsCollide() {
            List<ILoggingEvent> events = logEventsDuring(NotificationService.class,
                () -> saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision")));

            assertThat(events)
                .extracting(event -> event.getThrowableProxy().getMessage())
                .containsExactly("first collision", "second collision", "last collision");
        }

        @Test
        void saveNotification_shouldKeepTheLastCollisionAsTheCause_whenAllPersistenceAttemptsCollide() {
            DuplicateKeyException lastCollision = new DuplicateKeyException("last collision");

            Throwable thrown = saveWhoseThreeAttemptsCollide(lastCollision);

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasCauseReference(lastCollision);
        }

        @Test
        void saveNotification_shouldUpdateNotificationFieldsAndWriteEditedEvent_whenDraft() {
            String existingId = "507f191e810c19729de860ea";
            String referenceNumber = "GBN-AG-26-507F19";
            NotificationAggregate existing = NotificationAggregate.builder()
                .id(existingId)
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("STALE").build())
                    .build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Origin origin = Origin.builder()
                .countryCode("FR")
                .requiresRegionCode("false")
                .internalReference("REF456")
                .regionOfOriginCode("FR-75")
                .build();
            List<Commodity> commodities = List.of(Commodity.builder()
                .name("Fish")
                .typeOfCommodity("LIVE")
                .totalNoOfAnimals(5)
                .species(List.of(species()))
                .build());
            AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
            Transport transport = Transport.builder()
                .portOfEntry("ABERDEEN")
                .arrivalDate(LocalDate.parse("2026-01-01"))
                .transporter(transporters().getFirst())
                .build();
            LocalDate exitDate = LocalDate.parse("2026-01-15");

            NotificationDto updateDto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .concurrencyToken(0L)
                .origin(origin)
                .commodities(commodities)
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .additionalDetails(additionalDetails)
                .reasonForImport("PERMANENT")
                .cphNumber("123456789")
                .transport(transport)
                .consignment(consignments().getFirst())
                .purposeInInternalMarket("Breeding")
                .destinationCountry("DE")
                .portOfExit("GB DVR")
                .exitDate(exitDate)
                .build();

            Notification expectedNotification = Notification.builder()
                .origin(origin)
                .commodities(commodities)
                .consignor(consignors().getFirst())
                .destination(destinations().getFirst())
                .additionalDetails(additionalDetails)
                .reasonForImport("PERMANENT")
                .cphNumber("123456789")
                .transport(transport)
                .consignment(consignments().getFirst())
                .purposeInInternalMarket("Breeding")
                .destinationCountry("DE")
                .portOfExit("GB DVR")
                .exitDate(exitDate)
                .build();

            NotificationAggregate result = notificationService.saveNotification(updateDto, "trace-upd-001", null);

            assertThat(result.getNotification())
                .usingRecursiveComparison()
                .isEqualTo(expectedNotification);
            assertThat(result)
                .extracting(NotificationAggregate::getId, NotificationAggregate::getReferenceNumber,
                    NotificationAggregate::getStatus, NotificationAggregate::getConcurrencyToken)
                .containsExactly(existingId, referenceNumber, DRAFT, 0L);
            verify(notificationRepository).save(result);
            verify(outboxService).appendEvent(result, OutboxEventType.NOTIFICATION_EDITED, "trace-upd-001", null);
        }

        @Test
        void saveNotification_shouldPersistInlinePartyDetailsFromTheDto_byteFaithfully() {
            String referenceNumber = "GBN-AG-26-ORIG01";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            ConsignmentParty originParty = literalParty();
            ConsignmentParty contactParty = ConsignmentParty.builder()
                .name("Contact")
                .email("contact@example.com")
                .build();
            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .concurrencyToken(0L)
                .placeOfOrigin(originParty)
                .consignment(contactParty)
                .build();

            NotificationAggregate saved = notificationService.saveNotification(dto, "trace-orig-001", null);

            assertThat(saved.getNotification().getPlaceOfOrigin()).isEqualTo(originParty);
            assertThat(saved.getNotification().getConsignment()).isEqualTo(contactParty);
        }

        @Test
        void saveNotification_shouldCarryTheStoredPartyLiteralInEditedOutbox() {
            String referenceNumber = "GBN-AG-26-EDIT01";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .concurrencyToken(0L)
                .consignor(literalParty())
                .build();

            NotificationAggregate saved = notificationService.saveNotification(
                dto, "trace-edit-001", Actor.builder().organisationId(ORG_ID).build());

            assertThat(saved.getNotification().getConsignor()).isEqualTo(literalParty());
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                captor.capture(), eq(OutboxEventType.NOTIFICATION_EDITED), eq("trace-edit-001"),
                any());
            assertThat(captor.getValue().getNotification().getConsignor()).isEqualTo(literalParty());
        }

        @Test
        void saveNotification_shouldWriteEditedEvent_whenAmend() {
            String referenceNumber = "GBN-AG-26-AMEND1";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            notificationService.saveNotification(dto, "trace-amd-001", null);

            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_EDITED), eq("trace-amd-001"), eq(null));
        }

        @Test
        void saveNotification_shouldThrowBadRequest_whenStatusIsSubmitted() {
            String referenceNumber = "GBN-AG-26-SUBM01";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));

            NotificationDto dto = NotificationDto.builder().referenceNumber(referenceNumber).build();

            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-001", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldThrowNotFound_whenRefUnknown() {
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            NotificationDto dto = NotificationDto.builder().referenceNumber(referenceNumber).build();

            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-001", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldThrowBadRequest_whenConcurrencyTokenIsNullOnUpdate() {
            String referenceNumber = "GBN-AG-26-NULLVU";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(3L)
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber)
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("REF").build())
                .build();

            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-nvu", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("concurrencyToken");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            String referenceNumber = "GBN-AG-26-LOCK01";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(NotificationAggregate.builder()
                    .referenceNumber(referenceNumber).status(DRAFT).build()));
            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            assertThatThrownBy(() -> notificationService.saveNotification(dto, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });

            verify(lockProvider, times(3)).lock(any());
            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void saveNotification_shouldRetryAndSucceed_whenFirstLockAttemptFails() {
            String referenceNumber = "GBN-AG-26-RETRY1";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();
            NotificationAggregate saved = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class))).thenReturn(saved);

            when(lockProvider.lock(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mock(SimpleLock.class)));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            NotificationAggregate result = notificationService.saveNotification(dto, "trace-retry", null);

            assertThat(result).isEqualTo(saved);
            verify(lockProvider, times(2)).lock(any());
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_EDITED), any(), any());
        }

        @Test
        void saveNotification_shouldSaveBeforeAppendingOutboxEvent() {
            String referenceNumber = "GBN-AG-26-ORDER1";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(referenceNumber).concurrencyToken(0L).build();

            notificationService.saveNotification(dto, "trace-ord-001", null);

            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(existing);
            inOrder.verify(outboxService).appendEvent(existing, OutboxEventType.NOTIFICATION_EDITED, "trace-ord-001", null);
        }
    }

    @Nested
    class FindDashboardPage {

        @Test
        void findDashboardPage_shouldReturnEmptyPage() {
            Page<NotificationView> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(emptyPage);

            NotificationPageResponse result = notificationService.findDashboardPage(1, null, null);

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
            verify(notificationRepository, times(1))
                .findAllViewByStatusIn(eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class));
        }

        @Test
        void findDashboardPage_shouldExcludeDeletedNotifications() {
            NotificationView draft = notificationView()
                .referenceNumber("GBN-AG-26-000DFT")
                .status(DRAFT)
                .build();
            NotificationView submitted = notificationView()
                .referenceNumber("GBN-AG-26-000SUB")
                .status(SUBMITTED)
                .build();

            Page<NotificationView> page = new PageImpl<>(
                List.of(draft, submitted), PageRequest.of(0, 54), 0);

            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            NotificationPageResponse result = notificationService.findDashboardPage(1, null, null);

            assertThat(result.content()).hasSize(2);
            assertThat(result.content()).extracting(NotificationView::getStatus)
                .containsExactlyInAnyOrder(DRAFT, SUBMITTED);
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(54);
            verify(notificationRepository, times(1))
                .findAllViewByStatusIn(eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class));
        }

        @Test
        void findDashboardPage_shouldReturnTheViewWithItsReferenceNumberAndStatus() {
            NotificationView view = notificationView()
                .referenceNumber("GBN-AG-26-ABC123")
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("REF-1").build())
                .status(SUBMITTED)
                .build();
            Page<NotificationView> page = new PageImpl<>(
                List.of(view), PageRequest.of(0, 54), 1);
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            NotificationPageResponse result = notificationService.findDashboardPage(1, null, null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().getReferenceNumber()).isEqualTo(
                "GBN-AG-26-ABC123");
            assertThat(result.content().getFirst().getStatus()).isEqualTo(
                SUBMITTED);
        }

        @Test
        void findDashboardPage_shouldIncludeAmendNotifications() {
            NotificationView amend = notificationView()
                .referenceNumber("GBN-AG-26-000AMD")
                .status(AMEND)
                .build();

            Page<NotificationView> page = new PageImpl<>(
                List.of(amend), PageRequest.of(0, 54), 0);

            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(page);

            NotificationPageResponse result = notificationService.findDashboardPage(1, null, null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.content()).extracting(NotificationView::getStatus)
                .containsExactly(AMEND);
        }

        @Test
        void findDashboardPage_shouldUseCreatedAtSort_whenRequested() {
            Page<NotificationView> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), any(Pageable.class)))
                .thenReturn(emptyPage);

            notificationService.findDashboardPage(1, "createdAt,asc", null);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllViewByStatusIn(
                eq(List.of(DRAFT, SUBMITTED, AMEND)), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getSort().getOrderFor("created").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        }

        @Test
        void findDashboardPage_shouldReturnMatchingNotification_whenReferenceNumberProvided() {
            NotificationView draft = notificationView()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(DRAFT)
                .build();

            when(notificationRepository.findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ABC123", List.of(DRAFT, SUBMITTED, AMEND)))
                .thenReturn(Optional.of(draft));

            NotificationPageResponse result =
                notificationService.findDashboardPage(1, null, "GBN-AG-26-ABC123");

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().getReferenceNumber())
                .isEqualTo("GBN-AG-26-ABC123");
            assertThat(result.totalElements()).isEqualTo(1);
            verify(notificationRepository, never())
                .findAllViewByStatusIn(any(), any(Pageable.class));
        }

        @Test
        void findDashboardPage_shouldReturnEmptyPage_whenReferenceNumberNotFound() {
            when(notificationRepository.findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ZZZZZZ", List.of(DRAFT, SUBMITTED, AMEND)))
                .thenReturn(Optional.empty());

            NotificationPageResponse result =
                notificationService.findDashboardPage(1, null, "GBN-AG-26-ZZZZZZ");

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
            verify(notificationRepository, never())
                .findAllViewByStatusIn(any(), any(Pageable.class));
        }

        @Test
        void findDashboardPage_shouldTrimReferenceNumber_beforeLookup() {
            when(notificationRepository.findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ABC123", List.of(DRAFT, SUBMITTED, AMEND)))
                .thenReturn(Optional.empty());

            notificationService.findDashboardPage(1, null, "  GBN-AG-26-ABC123  ");

            verify(notificationRepository).findViewByReferenceNumberAndStatusIn(
                "GBN-AG-26-ABC123", List.of(DRAFT, SUBMITTED, AMEND));
        }
    }

    @Nested
    class FindFulfilmentsView {

        @Test
        void findFulfilmentsView_shouldReturnProjection_whenNotificationExists() {
            String ref = "GBN-AG-26-FUL001";
            NotificationFulfilmentsView view = mock(NotificationFulfilmentsView.class);
            when(notificationRepository.findFulfilmentsViewByReferenceNumber(ref))
                .thenReturn(Optional.of(view));

            NotificationFulfilmentsView result = notificationService.findFulfilmentsView(ref);

            assertThat(result).isSameAs(view);
        }

        @Test
        void findFulfilmentsView_shouldThrowNotFound_whenReferenceNumberUnknown() {
            String ref = "GBN-AG-26-ABSENT";
            when(notificationRepository.findFulfilmentsViewByReferenceNumber(ref))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.findFulfilmentsView(ref))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(ref);
        }
    }

    @Nested
    class FindAllReferenceNumbers {

        @Test
        void findAllReferenceNumbers_shouldReturnEmptyPage_whenNoNotificationsExist() {
            Page<NotificationReferenceOnly> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(0, 54), 0);
            when(notificationRepository.findAllReferencesBy(any(Pageable.class)))
                .thenReturn(emptyPage);

            ReferenceNumberPageResponse result = notificationService.findAllReferenceNumbers(0);

            assertThat(result.content()).isEmpty();
            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(54);
            assertThat(result.numberOfElements()).isZero();
            assertThat(result.totalElements()).isZero();
            assertThat(result.totalPages()).isZero();
            verify(notificationRepository, times(1)).findAllReferencesBy(any(Pageable.class));
        }

        @Test
        void findAllReferenceNumbers_shouldReturnPagedReferenceNumbers_whenNotificationsExist() {
            NotificationReferenceOnly ref1 = () -> "GBN-AG-26-ABC123";
            NotificationReferenceOnly ref2 = () -> "GBN-AG-26-XYZ456";
            Page<NotificationReferenceOnly> page = new PageImpl<>(
                List.of(ref1, ref2), PageRequest.of(0, 54), 2);
            when(notificationRepository.findAllReferencesBy(any(Pageable.class))).thenReturn(page);

            ReferenceNumberPageResponse result = notificationService.findAllReferenceNumbers(0);

            assertThat(result.content()).containsExactly("GBN-AG-26-ABC123", "GBN-AG-26-XYZ456");
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.totalPages()).isEqualTo(1);
            verify(notificationRepository, times(1)).findAllReferencesBy(any(Pageable.class));
        }

        @Test
        void findAllReferenceNumbers_shouldPassPageRequestWithAdminPageSizeAndNewestCreatedFirst() {
            Page<NotificationReferenceOnly> emptyPage = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(2, 54), 200);
            when(notificationRepository.findAllReferencesBy(any(Pageable.class)))
                .thenReturn(emptyPage);

            notificationService.findAllReferenceNumbers(2);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllReferencesBy(pageableCaptor.capture());
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

        private static final List<String> FOUND_REFERENCES = List.of("GBN-AG-26-000111", "GBN-AG-26-000222");

        private void deleteFoundReferences() {
            NotificationReferenceOnly notificationRef1 = () -> FOUND_REFERENCES.get(0);
            NotificationReferenceOnly notificationRef2 = () -> FOUND_REFERENCES.get(1);
            when(notificationRepository.findAllReferencesByReferenceNumberIn(FOUND_REFERENCES))
                .thenReturn(List.of(notificationRef1, notificationRef2));

            notificationService.deleteByReferenceNumbers(FOUND_REFERENCES,
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));
        }

        @Test
        void deleteByReferenceNumbers_shouldDeleteTheNotificationsAndTheirDocuments_whenAllFound() {
            deleteFoundReferences();

            verify(notificationRepository).deleteAllByReferenceNumberIn(FOUND_REFERENCES);
            verify(documentService).deleteForNotificationReferenceNumbers(FOUND_REFERENCES);
        }

        @Test
        void deleteByReferenceNumbers_shouldAuditASuccessWithTheReferencesAndCaller_whenAllFound() {
            deleteFoundReferences();

            ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
            verify(auditRepository).save(auditCaptor.capture());
            Audit saved = auditCaptor.getValue();
            assertThat(saved.getResult()).isEqualTo(Result.SUCCESS);
            assertThat(saved.getNotificationReferenceNumbers()).containsExactlyElementsOf(FOUND_REFERENCES);
            assertThat(saved.getNumberOfNotifications()).isEqualTo(2);
            assertThat(saved.getTraceId()).isEqualTo(TEST_TRACE_ID);
            assertThat(saved.getUserId()).isEqualTo(TEST_USER_ID);
        }

        @Test
        void deleteByReferenceNumbers_shouldThrowNotFoundException_whenOneIsMissing() {
            String existingRef = "GBN-AG-26-000111";
            String missingRef = "GBN-AG-26-MSNG00";
            NotificationReferenceOnly n1 = () -> existingRef;

            when(
                notificationRepository.findAllReferencesByReferenceNumberIn(List.of(existingRef, missingRef)))
                .thenReturn(List.of(n1));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            List<String> referenceNumbers = List.of(existingRef, missingRef);
            AuditContext auditContext = new AuditContext(TEST_TRACE_ID, TEST_USER_ID);

            assertThatThrownBy(
                () -> notificationService.deleteByReferenceNumbers(referenceNumbers, auditContext))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missingRef);

            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());

            ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
            verify(auditRepository).save(auditCaptor.capture());
            assertThat(auditCaptor.getValue().getResult()).isEqualTo(Result.FAILURE);
        }

        @Test
        void deleteByReferenceNumbers_shouldListAllMissingRefs_inExceptionMessage() {
            String missing1 = "GBN-AG-26-000AAA";
            String missing2 = "GBN-AG-26-000BBB";

            when(notificationRepository.findAllReferencesByReferenceNumberIn(List.of(missing1, missing2)))
                .thenReturn(Collections.emptyList());
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            List<String> referenceNumbers = List.of(missing1, missing2);
            AuditContext auditContext = new AuditContext(TEST_TRACE_ID, TEST_USER_ID);

            assertThatThrownBy(
                () -> notificationService.deleteByReferenceNumbers(referenceNumbers, auditContext))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing1)
                .hasMessageContaining(missing2);

            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(auditRepository).save(any(Audit.class));
        }

        @Test
        void deleteByReferenceNumbers_shouldDoNothing_whenListIsEmpty() {
            notificationService.deleteByReferenceNumbers(Collections.emptyList(),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            verify(notificationRepository, never()).findAllReferencesByReferenceNumberIn(anyList());
            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(auditRepository, never()).save(any(Audit.class));
        }

        @Test
        void deleteByReferenceNumbers_shouldCascadeDeleteDocuments_whenNotificationsDeleted() {
            String referenceNumber = "GBN-AG-26-000111";
            NotificationReferenceOnly notificationRef = () -> referenceNumber;

            when(notificationRepository.findAllReferencesByReferenceNumberIn(List.of(referenceNumber)))
                .thenReturn(List.of(notificationRef));
            when(auditRepository.save(any(Audit.class))).thenReturn(new Audit());

            notificationService.deleteByReferenceNumbers(List.of(referenceNumber),
                new AuditContext(TEST_TRACE_ID, TEST_USER_ID));

            InOrder inOrder = inOrder(notificationRepository, documentService);
            inOrder.verify(notificationRepository)
                .deleteAllByReferenceNumberIn(List.of(referenceNumber));
            inOrder.verify(documentService).deleteForNotificationReferenceNumbers(List.of(referenceNumber));
        }
    }

    @Nested
    class ExpiryStamping {

        @BeforeEach
        void stubCreate() {
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-TTL001");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private NotificationAggregate create(NotificationTtlConfig ttlConfig) {
            return create(buildService(ttlConfig));
        }

        private NotificationAggregate create(NotificationService service) {
            return service.saveNotification(
                NotificationDto.builder().origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("REF123").build()).build(), "", null);
        }

        @Test
        void createNotification_stampsExpireAtAnExactNumberOfElapsedDays_whenAClockChangeFallsInTheWindow() {
            ZoneId zoneWithAClockChangeOn28March = ZoneId.of("Europe/London");
            Clock threeDaysBeforeTheClockChange =
                Clock.fixed(Instant.parse("2027-03-25T12:00:00Z"), zoneWithAClockChangeOn28March);
            TimeZone originalTimeZone = TimeZone.getDefault();
            TimeZone.setDefault(TimeZone.getTimeZone(zoneWithAClockChangeOn28March));
            try {
                NotificationAggregate result = create(
                    buildService(new NotificationTtlConfig(7, "dev"), threeDaysBeforeTheClockChange));

                assertThat(result.getExpireAt()).isEqualTo(Instant.parse("2027-04-01T12:00:00Z"));
            } finally {
                TimeZone.setDefault(originalTimeZone);
            }
        }

        @Test
        void createNotification_stampsExpireAt_whenDaysConfiguredAndNotProd() {
            NotificationAggregate result = create(new NotificationTtlConfig(7, "dev"));

            assertThat(result.getExpireAt()).isEqualTo(result.getCreated().plus(7, ChronoUnit.DAYS));
        }

        @Test
        void createNotification_neverStampsExpireAt_inProd_whenDaysConfigured() {
            NotificationAggregate result = create(new NotificationTtlConfig(7, "prod"));

            assertThat(result.getExpireAt()).isNull();
        }

        @Test
        void createNotification_neverStampsExpireAt_inProd_caseInsensitive() {
            NotificationAggregate result = create(new NotificationTtlConfig(7, "PROD"));

            assertThat(result.getExpireAt()).isNull();
        }

        @Test
        void createNotification_doesNotStampExpireAt_whenDaysUnconfigured() {
            NotificationAggregate result = create(new NotificationTtlConfig(null, "dev"));

            assertThat(result.getExpireAt()).isNull();
        }
    }

    @Nested
    class ExpiryStampingLog {

        private List<String> messagesLoggedBy(NotificationTtlConfig ttlConfig) {
            return messagesOf(logEventsDuring(NotificationService.class, buildService(ttlConfig)::logExpiryStamping));
        }

        @Test
        void logExpiryStamping_shouldGiveDaysAndEnvironment_whenStampingIsOn() {
            assertThat(messagesLoggedBy(new NotificationTtlConfig(7, "dev")))
                .containsExactly("Notification expiry stamping is on: 7 days in environment dev");
        }

        @Test
        void logExpiryStamping_shouldNameProdAsTheReason_whenDaysAreSetInProd() {
            assertThat(messagesLoggedBy(new NotificationTtlConfig(7, "prod")))
                .containsExactly("Notification expiry stamping is off: environment prod is prod");
        }

        @Test
        void logExpiryStamping_shouldNameMissingDaysAsTheReason_whenNoDaysAreSetOutsideProd() {
            assertThat(messagesLoggedBy(new NotificationTtlConfig(null, "dev")))
                .containsExactly("Notification expiry stamping is off: no days set in environment dev");
        }

        @Test
        void logExpiryStamping_shouldListenForTheApplicationBeingReady() throws NoSuchMethodException {
            EventListener listener = NotificationService.class
                .getMethod("logExpiryStamping")
                .getAnnotation(EventListener.class);

            assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
        }
    }

    @Nested
    class SubmitNotification {

        @Test
        void submitNotification_shouldSetStatusToSubmittedAndSave() {
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN,
                "trace-001", null);

            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getUpdated()).isNotNull();
            assertThat(result.getPreAmendNotification()).isNull();
            verify(notificationRepository).save(notificationAggregate);
            verify(outboxService).appendEvent(notificationAggregate,
                OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null);
        }

        @Test
        void submitNotification_shouldCarryEveryPartyOnTheOutboxEvent_exactlyAsStored() {
            String referenceNumber = "GBN-AG-26-REF011";
            ConsignmentParty contact = ConsignmentParty.builder()
                .name("Contact")
                .email("contact@example.com")
                .phone("01632 960001")
                .address(Address.builder().addressLine1("1 Contact Road").countryCode("GB").build())
                .build();
            Notification stored = Notification.builder()
                .consignor(literalParty())
                .consignee(literalParty().toBuilder().name("Consignee").build())
                .importer(literalParty().toBuilder().name("Importer").build())
                .placeOfOrigin(literalParty().toBuilder().name("Origin").build())
                .destination(literalParty().toBuilder().name("Destination").build())
                .consignment(contact)
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-ref")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(stored)
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            Actor actor = Actor.builder().organisationId(ORG_ID).build();

            NotificationAggregate returned =
                notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-ref-001", actor);

            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                captor.capture(), eq(OutboxEventType.NOTIFICATION_SUBMITTED), eq("trace-ref-001"),
                eq(actor));
            assertThat(captor.getValue()).isSameAs(returned);
            Notification emitted = captor.getValue().getNotification();
            assertThat(emitted.getConsignor()).isEqualTo(stored.getConsignor());
            assertThat(emitted.getConsignee()).isEqualTo(stored.getConsignee());
            assertThat(emitted.getImporter()).isEqualTo(stored.getImporter());
            assertThat(emitted.getPlaceOfOrigin()).isEqualTo(stored.getPlaceOfOrigin());
            assertThat(emitted.getDestination()).isEqualTo(stored.getDestination());
            assertThat(emitted.getConsignment()).isEqualTo(contact);
            assertThat(returned.getNotification().getConsignor()).isEqualTo(literalParty());
        }

        @Test
        void submitNotification_shouldWriteOutboxEvent_afterSavingNotification() {
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-001", null);

            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(notificationAggregate);
            inOrder.verify(outboxService).appendEvent(any(NotificationAggregate.class),
                eq(OutboxEventType.NOTIFICATION_SUBMITTED), eq("trace-001"), eq(null));
        }

        @Test
        void submitNotification_shouldThrowOutboxWriteException_whenAppendEventFails() {
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new OutboxWriteException("Forced failure", "agg-id", 1L, "trace-001"))
                .when(outboxService).appendEvent(any(), any(), any(), any());

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class);
        }

        @Test
        void submitNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-001", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void submitNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-001", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });

            verify(lockProvider, times(3)).lock(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void submitNotification_shouldSetStatusToSubmittedAndSave_whenAmend() {
            String referenceNumber = "GBN-AG-26-AMEND1";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amend-1")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN,
                "trace-002", null);

            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
            verify(outboxService).appendEvent(any(NotificationAggregate.class),
                eq(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED), eq("trace-002"), eq(null));
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenAlreadySubmitted() {
            String referenceNumber = "GBN-AG-26-ALREADY";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-already")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-003", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenDeleted() {
            String referenceNumber = "GBN-AG-26-DELETED";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-deleted")
                .referenceNumber(referenceNumber)
                .status(NotificationStatus.DELETED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-004", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void submitNotification_shouldSaveAtTheExpectedConcurrencyToken() {
            String referenceNumber = "GBN-AG-26-TOKEN1";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-token")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .concurrencyToken(8L)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            ArgumentCaptor<NotificationAggregate> saved = ArgumentCaptor.forClass(NotificationAggregate.class);
            when(notificationRepository.save(saved.capture()))
                .thenThrow(new OptimisticLockingFailureException("stale"));

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, SUBMIT_TOKEN, "trace-005", null))
                .isInstanceOf(OptimisticLockingFailureException.class);

            assertThat(saved.getValue().getConcurrencyToken()).isEqualTo(SUBMIT_TOKEN);
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void submitNotification_shouldThrowBadRequest_whenConcurrencyTokenNull() {
            String referenceNumber = "GBN-AG-26-NOTOKN";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-no-token")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(
                () -> notificationService.submitNotification(referenceNumber, null, "trace-006", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("concurrencyToken");

            verify(notificationRepository, never()).save(any());
            verify(lockProvider, never()).lock(any());
        }
    }

    @Nested
    class AmendNotification {

        @Test
        void amendNotification_shouldSetStatusToAmendAndSave_whenSubmitted() {
            String referenceNumber = "GBN-AG-26-AMD001";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-1")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .fulfilments(List.of(new Document("obligationId", "amd-1")))
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.amendNotification(referenceNumber,
                "trace-amd-1", null);

            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getUpdated()).isNotNull();
            assertThat(result.getPreAmendNotification()).isNotNull();
            assertThat(result.getPreAmendNotification().getOrigin()).isNull();
            verify(notificationRepository).save(notificationAggregate);
            verify(outboxService).appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED, "trace-amd-1", null);
        }

        @Test
        void amendNotification_shouldCapturePreAmendSnapshotFromLiveContent() {
            String referenceNumber = "GBN-AG-26-AMD008";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-8")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("LIVE-REF").build())
                    .build())
                .fulfilments(List.of(new Document("obligationId", "amd-8")))
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.amendNotification(referenceNumber, "trace-amd-8", null);

            assertThat(notificationAggregate.getPreAmendNotification()).isNotNull();
            assertThat(notificationAggregate.getPreAmendNotification().getOrigin().getInternalReference())
                .isEqualTo("LIVE-REF");
        }

        @Test
        void amendNotification_shouldWriteOutboxEvent_afterSavingNotification() {
            String referenceNumber = "GBN-AG-26-AMD002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-2")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .fulfilments(List.of(new Document("obligationId", "amd-2")))
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.amendNotification(referenceNumber, "trace-amd-2", null);

            InOrder inOrder = inOrder(notificationRepository, outboxService);
            inOrder.verify(notificationRepository).save(notificationAggregate);
            inOrder.verify(outboxService).appendEvent(notificationAggregate, OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED, "trace-amd-2", null);
        }

        @Test
        void amendNotification_shouldThrow_whenFulfilmentsMissing() {
            String referenceNumber = "GBN-AG-26-AMD009";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-9")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-9", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fulfilments payload is missing");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void amendNotification_shouldThrowBadRequest_whenDraft() {
            String referenceNumber = "GBN-AG-26-AMD003";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-3")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-3", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DRAFT");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void amendNotification_shouldThrowBadRequest_whenAlreadyAmend() {
            String referenceNumber = "GBN-AG-26-AMD004";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-4")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-4", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("AMEND");

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
        }

        @Test
        void amendNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-5", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
            verify(outboxService, never()).appendEvent(any(), any(), any(), any());
            verify(lockProvider, never()).lock(any());
        }

        @Test
        void amendNotification_shouldThrowOutboxWriteException_whenLockNotAcquired() {
            String referenceNumber = "GBN-AG-26-AMD006";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-6")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .fulfilments(List.of(new Document("obligationId", "amd-6")))
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-6", null))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG." + referenceNumber);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-amd-6");
                });

            verify(lockProvider, times(3)).lock(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void amendNotification_shouldThrowOutboxWriteException_whenAppendEventFails() {
            String referenceNumber = "GBN-AG-26-AMD007";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amd-7")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .fulfilments(List.of(new Document("obligationId", "amd-7")))
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            doThrow(new OutboxWriteException("Forced failure", "agg-id", 1L, "trace-amd-7"))
                .when(outboxService).appendEvent(any(), any(), any(), any());

            assertThatThrownBy(
                () -> notificationService.amendNotification(referenceNumber, "trace-amd-7", null))
                .isInstanceOf(OutboxWriteException.class);
        }
    }

    @Nested
    class CancelAmendNotification {

        @Test
        void cancelAmendNotification_shouldRestorePreAmendSnapshotAndSetSubmitted() {
            String referenceNumber = "GBN-AG-26-CAN001";
            Notification preAmendSnapshot =Notification.builder()
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("ORIGINAL-REF").build())
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-can-1")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("FR").requiresRegionCode("false").internalReference("EDITED-REF").build())
                    .build())
                .preAmendNotification(preAmendSnapshot)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.cancelAmendNotification(referenceNumber, "trace", null);

            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getPreAmendNotification()).isNull();
            assertThat(result.getNotification().getOrigin().getInternalReference()).isEqualTo("ORIGINAL-REF");
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_AMENDMENT_CANCELLED), any(), any());
        }

        @Test
        void cancelAmend_shouldPutThePreAmendSnapshotOnTheOutboxEvent_notTheAmendedParty() {
            Notification preAmendSnapshot = Notification.builder()
                .placeOfOrigin(ConsignmentParty.builder()
                    .name("Frozen Origin")
                    .build())
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-can-freeze")
                .referenceNumber("GBN-AG-26-CANF01")
                .status(AMEND)
                .notification(Notification.builder()
                    .placeOfOrigin(ConsignmentParty.builder().name("Amended Origin").build())
                    .build())
                .preAmendNotification(preAmendSnapshot)
                .build();

            when(notificationRepository.findByReferenceNumber("GBN-AG-26-CANF01"))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result =
                notificationService.cancelAmendNotification("GBN-AG-26-CANF01", "trace", null);

            assertThat(result.getNotification().getPlaceOfOrigin().getName())
                .isEqualTo("Frozen Origin");
            assertThat(result.getPreAmendNotification()).isNull();
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                captor.capture(), eq(OutboxEventType.NOTIFICATION_AMENDMENT_CANCELLED), eq("trace"), eq(null));
            assertThat(captor.getValue().getNotification().getPlaceOfOrigin().getName())
                .isEqualTo("Frozen Origin");
        }

        @Test
        void cancelAmendNotification_shouldThrowBadRequest_whenNotAmend() {
            String referenceNumber = "GBN-AG-26-CAN002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(() -> notificationService.cancelAmendNotification(referenceNumber, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void cancelAmendNotification_shouldThrowBadRequest_whenPreAmendSnapshotMissing() {
            String referenceNumber = "GBN-AG-26-CAN003";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .preAmendNotification(null)
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));

            assertThatThrownBy(() -> notificationService.cancelAmendNotification(referenceNumber, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pre-amend");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void cancelAmendNotification_shouldThrowNotFound_whenReferenceNumberUnknown() {
            when(notificationRepository.findByReferenceNumber("GBN-AG-26-ABSENT"))
                .thenReturn(Optional.empty());

            assertThatThrownBy(
                () -> notificationService.cancelAmendNotification("GBN-AG-26-ABSENT", "trace", null))
                .isInstanceOf(NotFoundException.class);

            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    class CopyNotification {

        @Test
        void copyNotification_shouldCreateNewDraftWithNewReferenceNumber() {
            String sourceRef = "GBN-AG-26-SRC001";
            String newRef = "GBN-AG-26-NEW001";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("IE").requiresRegionCode("no").internalReference("INT-REF-DO-NOT-COPY").build())
                    .build())
                .status(NotificationStatus.DRAFT)
                .concurrencyToken(0L)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.copyNotification(sourceRef, 0L, "trace", null);

            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void copyNotification_shouldCreateNewDraftFromSubmittedSource() {
            String sourceRef = "GBN-AG-26-SUB001";
            String newRef = "GBN-AG-26-NEW002";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("IE").requiresRegionCode("no").internalReference("INT-REF-DO-NOT-COPY").build())
                    .build())
                .status(NotificationStatus.SUBMITTED)
                .concurrencyToken(0L)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.copyNotification(sourceRef, 0L, "trace", null);

            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void copyNotification_shouldRetainCopiedFields() {
            String sourceRef = "GBN-AG-26-SRC002";
            Origin origin = Origin.builder()
                .countryCode("DE")
                .requiresRegionCode("yes")
                .internalReference("INTERNAL-REF")
                .build();
            AdditionalDetails additionalDetails = new AdditionalDetails("Breeding", "yes");
            Commodity commodity =Commodity.builder()
                .name("Live bovine animals")
                .typeOfCommodity("LIVE")
                .totalNoOfAnimals(10)
                .totalNoOfPackages(5)
                .species(List.of(species()))
                .build();

            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .concurrencyToken(0L)
                .notification(Notification.builder()
                    .origin(origin)
                    .commodities(List.of(commodity))
                    .reasonForImport("internalMarket")
                    .additionalDetails(additionalDetails)
                    .consignor(consignors().getFirst())
                    .destination(destinations().getFirst())
                    .cphNumber("12/345/6789")
                    .transport(Transport.builder()
                        .portOfEntry("GBDVR")
                        .arrivalDate(LocalDate.parse("2026-05-01"))
                        .transporter(transporters().getFirst())
                        .build())
                    .consignment(consignments().getFirst())
                    .build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-CPY002");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(notificationRepository).save(captor.capture());
            NotificationAggregate saved = captor.getValue();

            assertThat(saved.getStatus()).isEqualTo(NotificationStatus.DRAFT);
            assertThat(saved.getNotification().getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(saved.getNotification().getOrigin().getRequiresRegionCode()).isEqualTo("yes");
            assertThat(saved.getNotification().getReasonForImport()).isEqualTo("internalMarket");
            assertThat(saved.getNotification().getCommodities().getFirst().getName()).isEqualTo("Live bovine animals");
            assertThat(saved.getNotification().getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
            assertThat(saved.getNotification().getConsignor()).isEqualTo(consignors().getFirst());
            assertThat(saved.getNotification().getDestination()).isEqualTo(destinations().getFirst());
            assertThat(saved.getNotification().getCphNumber()).isEqualTo("12/345/6789");
        }

        @Test
        void copyNotification_shouldResetExcludedFields() {
            String sourceRef = "GBN-AG-26-SRC003";
            Commodity commodity =Commodity.builder()
                .name("Cattle")
                .commodityCode("0102")
                .description("Live cattle")
                .typeOfCommodity("LIVE")
                .totalNoOfAnimals(10)
                .totalNoOfPackages(5)
                .species(List.of(species()))
                .build();
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .concurrencyToken(0L)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("FR").requiresRegionCode("no").internalReference("DO-NOT-COPY").build())
                    .commodities(List.of(commodity))
                    .additionalDetails(new AdditionalDetails("Slaughter", "no"))
                    .transport(Transport.builder()
                        .portOfEntry("GBFXT")
                        .arrivalDate(LocalDate.parse("2026-06-01"))
                        .build())
                    .consignment(consignments().getFirst())
                    .build())
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn("GBN-AG-26-CPY003");
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(notificationRepository).save(captor.capture());
            NotificationAggregate saved = captor.getValue();

            assertThat(saved.getNotification().getOrigin().getInternalReference()).isNull();
            assertThat(saved.getNotification().getAdditionalDetails().getUnweanedAnimals()).isNull();
            assertThat(saved.getNotification().getTransport()).isNull();
            assertThat(saved.getNotification().getConsignment()).isNull();
            Commodity copiedCommodity =saved.getNotification().getCommodities().getFirst();
            assertThat(copiedCommodity.getName()).isEqualTo("Cattle");
            assertThat(copiedCommodity.getCommodityCode()).isEqualTo("0102");
            assertThat(copiedCommodity.getDescription()).isEqualTo("Live cattle");
            assertThat(copiedCommodity.getTypeOfCommodity()).isEqualTo("LIVE");
            assertThat(copiedCommodity.getTotalNoOfAnimals()).isNull();
            assertThat(copiedCommodity.getTotalNoOfPackages()).isNull();
            assertThat(copiedCommodity.getSpecies()).isEmpty();
        }

        @Test
        void copyNotification_shouldCreateNewDraftFromAmendSource() {
            String sourceRef = "GBN-AG-26-AMD001";
            String newRef = "GBN-AG-26-NEW-AMD";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("IE").requiresRegionCode("no").internalReference("INT-REF-DO-NOT-COPY").build())
                    .build())
                .status(AMEND)
                .concurrencyToken(0L)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.copyNotification(sourceRef, 0L, "trace", null);

            assertThat(result.getReferenceNumber()).isEqualTo(newRef);
            assertThat(result.getStatus()).isEqualTo(DRAFT);
        }

        @Test
        void copyNotification_shouldThrowNotFoundException_whenSourceNotFound() {
            String sourceRef = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, 0L, "trace", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(sourceRef);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowBadRequestException_whenSourceIsDeleted() {
            String sourceRef = "GBN-AG-26-DELETED";
            NotificationAggregate deleted = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DELETED)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(deleted));

            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, 0L, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowIllegalState_whenExpectedConcurrencyTokenIsNull() {
            String sourceRef = "GBN-AG-26-NULLEV";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(5L)
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));

            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, null, "trace", null))
                .isInstanceOf(IllegalStateException.class);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldThrowIllegalState_whenPersistedConcurrencyTokenIsNull() {
            String sourceRef = "GBN-AG-26-NULLPV";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .build();
            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));

            assertThatThrownBy(() -> notificationService.copyNotification(sourceRef, 0L, "trace", null))
                .isInstanceOf(IllegalStateException.class);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copyNotification_shouldEmitNotificationCreated_forNewNotification() {
            String sourceRef = "GBN-AG-26-CPY-SRC";
            String newRef = "GBN-AG-26-CPY-NEW";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(0L)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_CREATED), any(), any());
            verifyNoMoreInteractions(outboxService);
        }

        @Test
        void copyNotification_shouldPersistAndEmitTheSourceConsignorLiteral() {
            String sourceRef = "GBN-AG-26-CPY-REF";
            String newRef = "GBN-AG-26-CPY-OUT";
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(SUBMITTED)
                .notification(Notification.builder()
                    .consignor(literalParty())
                    .build())
                .concurrencyToken(0L)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Actor actor = Actor.builder().organisationId(ORG_ID).build();

            notificationService.copyNotification(sourceRef, 0L, "trace", actor);

            ArgumentCaptor<NotificationAggregate> savedCaptor =
                ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(notificationRepository).save(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getNotification().getConsignor())
                .isEqualTo(literalParty());

            ArgumentCaptor<NotificationAggregate> outboxCaptor =
                ArgumentCaptor.forClass(NotificationAggregate.class);
            verify(outboxService).appendEvent(
                outboxCaptor.capture(), eq(OutboxEventType.NOTIFICATION_CREATED), eq("trace"), eq(actor));
            assertThat(outboxCaptor.getValue().getNotification().getConsignor())
                .isEqualTo(literalParty());
        }
    }

    @Nested
    class Fulfilments {

        @Test
        void replace_shouldReplaceContentIncludingFulfilments_whenDraft() {
            String ref = "GBN-AG-26-REP001";
            NotificationAggregate existing = NotificationAggregate.builder()
                .id("db-id-1")
                .referenceNumber(ref)
                .status(DRAFT)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("FR").requiresRegionCode("no").internalReference("OLD").build())
                    .build())
                .build();
            List<Document> newFulfilments = List.of(new Document("obligationId", "abc"));
            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(ref)
                .concurrencyToken(0L)
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("NEW").build())
                .fulfilments(newFulfilments)
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.replace(ref, dto, "trace", null);

            assertThat(result.getNotification().getOrigin().getCountryCode()).isEqualTo("GB");
            assertThat(result.getFulfilments()).isEqualTo(newFulfilments);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(existing);
            verify(outboxService).appendEvent(
                any(NotificationAggregate.class), eq(OutboxEventType.NOTIFICATION_EDITED), eq("trace"), any());
        }

        @Test
        void replace_shouldReplaceContentIncludingFulfilments_whenAmend() {
            String ref = "GBN-AG-26-REP002";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();
            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(ref)
                .concurrencyToken(0L)
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("AMEND-EDIT").build())
                .fulfilments(List.of(new Document("obligationId", "xyz")))
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.replace(ref, dto, "trace", null);

            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getNotification().getOrigin().getInternalReference()).isEqualTo("AMEND-EDIT");
            assertThat(result.getFulfilments()).hasSize(1);
        }

        @Test
        void replace_shouldThrowBadRequest_whenSubmitted() {
            String ref = "GBN-AG-26-REP003";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();
            NotificationDto dto = NotificationDto.builder().referenceNumber(ref).build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> notificationService.replace(ref, dto, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUBMITTED");
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void replace_shouldThrowBadRequest_whenDeleted() {
            String ref = "GBN-AG-26-REP004";
            NotificationAggregate existing = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(DELETED)
                .notification(Notification.builder().build())
                .build();
            NotificationDto dto = NotificationDto.builder().referenceNumber(ref).build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> notificationService.replace(ref, dto, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETED");
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void replace_shouldThrowNotFound_whenReferenceUnknown() {
            when(notificationRepository.findByReferenceNumber("GBN-AG-26-ABSENT"))
                .thenReturn(Optional.empty());
            NotificationDto dto = NotificationDto.builder().build();

            assertThatThrownBy(
                () -> notificationService.replace("GBN-AG-26-ABSENT", dto, "trace", null))
                .isInstanceOf(NotFoundException.class);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void replace_shouldThrowBadRequest_whenConcurrencyTokenIsNull() {
            String ref = "GBN-AG-26-NULLVR";
            NotificationAggregate existing = NotificationAggregate.builder()
                .id("db-id-nv")
                .referenceNumber(ref)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .concurrencyToken(3L)
                .build();
            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(existing));

            NotificationDto dto = NotificationDto.builder()
                .referenceNumber(ref)
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("REF").build())
                .build();

            assertThatThrownBy(() -> notificationService.replace(ref, dto, "trace", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("concurrencyToken");

            verify(notificationRepository, never()).save(any());
        }

        @Test
        void amend_shouldSnapshotFulfilmentsIntoPreAmendFulfilments() {
            String ref = "GBN-AG-26-AMD001";
            List<Document> fulfilments = new ArrayList<>(
                List.of(new Document("obligationId", "abc")));
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-a")
                .referenceNumber(ref)
                .status(SUBMITTED)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("REF").build())
                    .build())
                .fulfilments(fulfilments)
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.amendNotification(ref, "trace", null);

            assertThat(result.getStatus()).isEqualTo(AMEND);
            assertThat(result.getPreAmendFulfilments()).isEqualTo(fulfilments);
            fulfilments.add(new Document("obligationId", "post-snapshot"));
            assertThat(result.getPreAmendFulfilments()).hasSize(1);
        }

        @Test
        void amend_shouldCapturePreAmendSnapshot_withoutMutatingLiveEdits() {
            String ref = "GBN-AG-26-AMD002";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-b")
                .referenceNumber(ref)
                .status(SUBMITTED)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("LIVE-REF").build())
                    .commodities(List.of(Commodity.builder().name("Cattle").build()))
                    .reasonForImport("PERMANENT")
                    .cphNumber("12/345/6789")
                    .build())
                .fulfilments(List.of(new Document("obligationId", "amd-snapshot")))
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.amendNotification(ref, "trace", null);

            assertThat(result.getPreAmendNotification()).isNotNull();
            assertThat(result.getPreAmendNotification().getOrigin().getInternalReference())
                .isEqualTo("LIVE-REF");
            assertThat(result.getPreAmendNotification().getCommodities().getFirst().getName())
                .isEqualTo("Cattle");

            result.getNotification().getOrigin().setInternalReference("EDITED");
            result.getNotification().getCommodities().getFirst().setName("MutatedCattle");
            result.getNotification().setReasonForImport("CHANGED");
            result.getNotification().setCphNumber("99/999/9999");

            assertThat(result.getPreAmendNotification().getOrigin().getInternalReference())
                .isEqualTo("LIVE-REF");
            assertThat(result.getPreAmendNotification().getCommodities().getFirst().getName())
                .isEqualTo("Cattle");
            assertThat(result.getPreAmendNotification().getReasonForImport())
                .isEqualTo("PERMANENT");
            assertThat(result.getPreAmendNotification().getCphNumber())
                .isEqualTo("12/345/6789");
        }

        @Test
        void cancelAmend_shouldRestoreFulfilmentsAndPreserveOriginalSubmittedAt() {
            String ref = "GBN-AG-26-CAN001";
            Instant originalSubmittedAt = Instant.parse("2026-04-15T10:00:00Z");
            List<Document> priorFulfilments = List.of(new Document("obligationId", "prior"));
            Notification preAmendSnapshot =Notification.builder()
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("ORIGINAL").build())
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-c")
                .referenceNumber(ref)
                .status(AMEND)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("FR").requiresRegionCode("yes").internalReference("EDITED").build())
                    .build())
                .preAmendNotification(preAmendSnapshot)
                .preAmendFulfilments(new ArrayList<>(priorFulfilments))
                .fulfilments(List.of(new Document("obligationId", "in-flight-edit")))
                .submittedAt(originalSubmittedAt)
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.cancelAmendNotification(ref, "trace", null);

            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getPreAmendNotification()).isNull();
            assertThat(result.getPreAmendFulfilments()).isNull();
            assertThat(result.getFulfilments()).isEqualTo(priorFulfilments);
            assertThat(result.getSubmittedAt()).isEqualTo(originalSubmittedAt);
            assertThat(result.getNotification().getOrigin().getInternalReference()).isEqualTo("ORIGINAL");
        }

        @Test
        void deepCopyFulfilments_shouldProduceIndependentCopy_underNestedMutation() {
            Document nestedRecord = new Document("fulfilmentId", "line0").append("value", "5");
            Document outer = new Document("obligationId", "packages")
                .append("records", new ArrayList<>(List.of(nestedRecord)));
            List<Document> source = new ArrayList<>(List.of(outer));

            List<Document> copy = NotificationService.deepCopyFulfilments(source);

            source.get(0).put("obligationId", "mutated");
            source.get(0).getList("records", Document.class).add(new Document("fulfilmentId", "line1"));
            source.get(0).getList("records", Document.class).get(0).put("value", "999");

            assertThat(copy).isNotSameAs(source);
            Document copiedOuter = copy.get(0);
            assertThat(copiedOuter).isNotSameAs(outer);
            assertThat(copiedOuter.getString("obligationId")).isEqualTo("packages");
            assertThat(copiedOuter.getList("records", Document.class)).hasSize(1);
            assertThat(copiedOuter.getList("records", Document.class).get(0).getString("value")).isEqualTo("5");
        }

        @Test
        void submittedAt_shouldBeStamped_onFirstSubmission() {
            String ref = "GBN-AG-26-SBMT02";
            givenStoredNotificationAwaitingSave(ref, DRAFT, null);

            NotificationAggregate result = notificationService.submitNotification(ref, SUBMIT_TOKEN, "trace", null);

            assertThat(result.getSubmittedAt()).isNotNull();
        }

        @Test
        void submittedAt_shouldStayAtTheEarlierSubmission_whenAmendmentIsRequested() {
            String ref = "GBN-AG-26-SBMT03";
            Instant earlierSubmission = Instant.parse("2026-01-01T09:00:00Z");
            givenStoredNotificationAwaitingSave(ref, SUBMITTED, earlierSubmission);

            NotificationAggregate result = notificationService.amendNotification(ref, "trace", null);

            assertThat(result.getSubmittedAt()).isEqualTo(earlierSubmission);
        }

        @Test
        void submittedAt_shouldMoveOnFromTheEarlierSubmission_whenResubmittedFromAmend() {
            String ref = "GBN-AG-26-SBMT04";
            Instant earlierSubmission = Instant.parse("2026-01-01T09:00:00Z");
            givenStoredNotificationAwaitingSave(ref, AMEND, earlierSubmission);

            NotificationAggregate result = notificationService.submitNotification(ref, SUBMIT_TOKEN, "trace", null);

            assertThat(result.getSubmittedAt()).isAfter(earlierSubmission);
        }

        private void givenStoredNotificationAwaitingSave(
            String ref, NotificationStatus status, Instant submittedAt) {
            NotificationAggregate stored = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(status)
                .notification(Notification.builder().build())
                .fulfilments(List.of(new Document("obligationId", "stored")))
                .submittedAt(submittedAt)
                .build();
            when(notificationRepository.findByReferenceNumber(ref)).thenReturn(Optional.of(stored));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void submit_shouldClearPreAmendSnapshots_andKeepAmendedFulfilments_whenSubmittingFromAmend() {
            String ref = "GBN-AG-26-SBM001";
            Notification preAmendSnapshot = Notification.builder()
                .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("PRIOR").build())
                .build();
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("db-id-s")
                .referenceNumber(ref)
                .status(AMEND)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("FR").requiresRegionCode("yes").internalReference("AMENDED").build())
                    .build())
                .preAmendNotification(preAmendSnapshot)
                .preAmendFulfilments(new ArrayList<>(
                    List.of(new Document("obligationId", "prior"))))
                .fulfilments(List.of(new Document("obligationId", "current")))
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.submitNotification(ref, SUBMIT_TOKEN, "trace", null);

            assertThat(result.getStatus()).isEqualTo(SUBMITTED);
            assertThat(result.getSubmittedAt()).isNotNull();
            assertThat(result.getPreAmendNotification()).isNull();
            assertThat(result.getPreAmendFulfilments()).isNull();
            assertThat(result.getFulfilments()).extracting("obligationId").containsExactly("current");
        }

        @Test
        void softDelete_shouldBeIdempotent_onAlreadyDeleted() {
            String ref = "GBN-AG-26-DEL001";
            NotificationAggregate alreadyDeleted = NotificationAggregate.builder()
                .referenceNumber(ref)
                .status(DELETED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(ref))
                .thenReturn(Optional.of(alreadyDeleted));

            NotificationAggregate result = notificationService.softDeleteNotification(ref, "trace", null);

            assertThat(result).isSameAs(alreadyDeleted);
            assertThat(result.getStatus()).isEqualTo(DELETED);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void copy_shouldCarryFulfilmentsAcross() {
            String sourceRef = "GBN-AG-26-CPY001";
            String newRef = "GBN-AG-26-CPY002";
            List<Document> sourceFulfilments = new ArrayList<>(
                List.of(new Document("obligationId", "source")));
            NotificationAggregate source = NotificationAggregate.builder()
                .referenceNumber(sourceRef)
                .status(SUBMITTED)
                .concurrencyToken(0L)
                .notification(Notification.builder()
                    .origin(Origin.builder().countryCode("GB").requiresRegionCode("no").internalReference("SOURCE-REF").build())
                    .build())
                .fulfilments(sourceFulfilments)
                .build();

            when(notificationRepository.findByReferenceNumber(sourceRef))
                .thenReturn(Optional.of(source));
            when(referenceNumberGenerator.generate()).thenReturn(newRef);
            ArgumentCaptor<NotificationAggregate> captor = ArgumentCaptor.forClass(NotificationAggregate.class);
            when(notificationRepository.save(captor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.copyNotification(sourceRef, 0L, "trace", null);

            NotificationAggregate saved = captor.getValue();
            assertThat(saved.getReferenceNumber()).isEqualTo(newRef);
            assertThat(saved.getFulfilments()).isEqualTo(sourceFulfilments);
            sourceFulfilments.add(new Document("obligationId", "post-copy"));
            assertThat(saved.getFulfilments()).hasSize(1);
        }
    }

    @Nested
    class SoftDeleteNotification {

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenDraft() {
            String referenceNumber = "GBN-AG-26-ABC123";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-001")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.softDeleteNotification(referenceNumber, "trace", null);

            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenSubmitted() {
            String referenceNumber = "GBN-AG-26-ABC456";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-002")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.softDeleteNotification(referenceNumber, "trace", null);

            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
        }

        @Test
        void softDeleteNotification_shouldSetStatusToDeletedAndSave_whenAmend() {
            String referenceNumber = "GBN-AG-26-ABCAMD";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-amend-del")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            NotificationAggregate result = notificationService.softDeleteNotification(referenceNumber, "trace", null);

            assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationRepository).save(notificationAggregate);
        }

        @Test
        void softDeleteNotification_shouldEmitNotificationDeleted_whenDraft() {
            String referenceNumber = "GBN-AG-26-DEL-DRAFT";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-del-draft")
                .referenceNumber(referenceNumber)
                .status(DRAFT)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.softDeleteNotification(referenceNumber, "trace", null);

            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_DELETED), any(), any());
        }

        @Test
        void softDeleteNotification_shouldEmitNotificationSubmissionDeleted_whenSubmitted() {
            String referenceNumber = "GBN-AG-26-DEL-SUB";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-del-sub")
                .referenceNumber(referenceNumber)
                .status(SUBMITTED)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.softDeleteNotification(referenceNumber, "trace", null);

            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_SUBMISSION_DELETED), any(), any());
        }

        @Test
        void softDeleteNotification_shouldEmitNotificationSubmissionDeleted_whenAmend() {
            String referenceNumber = "GBN-AG-26-DEL-AMD";
            NotificationAggregate notificationAggregate = NotificationAggregate.builder()
                .id("notif-id-del-amd")
                .referenceNumber(referenceNumber)
                .status(AMEND)
                .notification(Notification.builder().build())
                .build();

            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.of(notificationAggregate));
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            notificationService.softDeleteNotification(referenceNumber, "trace", null);

            verify(outboxService).appendEvent(any(), eq(OutboxEventType.NOTIFICATION_SUBMISSION_DELETED), any(), any());
        }

        @Test
        void softDeleteNotification_shouldThrowNotFoundException_whenReferenceNumberUnknown() {
            String referenceNumber = "GBN-AG-26-ABSENT";
            when(notificationRepository.findByReferenceNumber(referenceNumber))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.softDeleteNotification(referenceNumber, "trace", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(referenceNumber);

            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    class StatusChangeLog {

        private static final String REFERENCE = "GBN-AG-26-LOG001";

        private NotificationAggregate repositoryHoldsAndEchoesSaves(NotificationStatus status) {
            NotificationAggregate stored = NotificationAggregate.builder()
                .referenceNumber(REFERENCE)
                .status(status)
                .notification(Notification.builder().build())
                .fulfilments(new ArrayList<>())
                .build();
            when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(stored));
            lenient().when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            return stored;
        }

        private List<String> messagesLoggedDuring(Runnable action) {
            return messagesOf(logEventsDuring(NotificationService.class, action));
        }

        @Test
        void softDeleteNotification_shouldLogTheReferenceAndNothingChanged_whenAlreadyDeleted() {
            repositoryHoldsAndEchoesSaves(DELETED);

            List<String> messages = messagesLoggedDuring(
                () -> notificationService.softDeleteNotification(REFERENCE, "trace", null));

            assertThat(messages).containsExactly("Notification GBN-AG-26-LOG001 is already DELETED, nothing changed");
        }

        @Test
        void softDeleteNotification_shouldLogTheStatusChange() {
            repositoryHoldsAndEchoesSaves(DRAFT);

            List<String> messages = messagesLoggedDuring(
                () -> notificationService.softDeleteNotification(REFERENCE, "trace", null));

            assertThat(messages).containsExactly("Notification GBN-AG-26-LOG001 status changed from DRAFT to DELETED");
        }

        @Test
        void submitNotification_shouldLogTheStatusChange() {
            repositoryHoldsAndEchoesSaves(AMEND);

            List<String> messages = messagesLoggedDuring(
                () -> notificationService.submitNotification(REFERENCE, SUBMIT_TOKEN, "trace", null));

            assertThat(messages).containsExactly("Notification GBN-AG-26-LOG001 status changed from AMEND to SUBMITTED");
        }

        @Test
        void amendNotification_shouldLogTheStatusChange() {
            repositoryHoldsAndEchoesSaves(SUBMITTED);

            List<String> messages = messagesLoggedDuring(
                () -> notificationService.amendNotification(REFERENCE, "trace", null));

            assertThat(messages).containsExactly("Notification GBN-AG-26-LOG001 status changed from SUBMITTED to AMEND");
        }

        @Test
        void cancelAmendNotification_shouldLogTheStatusChange() {
            repositoryHoldsAndEchoesSaves(AMEND).setPreAmendNotification(Notification.builder().build());

            List<String> messages = messagesLoggedDuring(
                () -> notificationService.cancelAmendNotification(REFERENCE, "trace", null));

            assertThat(messages).containsExactly("Notification GBN-AG-26-LOG001 status changed from AMEND to SUBMITTED");
        }

        @Test
        void replace_shouldLogOnlyTheUpdate_whenTheStatusStaysTheSame() {
            repositoryHoldsAndEchoesSaves(DRAFT);

            List<String> messages = messagesLoggedDuring(() -> notificationService.replace(REFERENCE,
                NotificationDto.builder().concurrencyToken(SUBMIT_TOKEN).build(), "trace", null));

            assertThat(messages).containsExactly("Updating notification GBN-AG-26-LOG001");
        }

        @Test
        void saveNotification_shouldLogOnlyTheSavedReference_whenCreating() {
            when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
            when(notificationRepository.save(any(NotificationAggregate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            List<String> messages = messagesLoggedDuring(
                () -> notificationService.saveNotification(NotificationDto.builder().build(), "trace", null));

            assertThat(messages).containsExactly("NotificationAggregate saved with reference number: GBN-AG-26-LOG001");
        }
    }

    private static NotificationViewBuilder notificationView() {
        return new NotificationViewBuilder();
    }

    private static final class NotificationViewBuilder {
        private String referenceNumber;
        private NotificationStatus status;
        private Origin origin;

        NotificationViewBuilder referenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; return this; }
        NotificationViewBuilder status(NotificationStatus status) { this.status = status; return this; }
        NotificationViewBuilder origin(Origin origin) { this.origin = origin; return this; }

        NotificationView build() {
            return new NotificationViewData(
                referenceNumber, 0L, status, null,
                origin, null, null, null, null);
        }
    }
}
