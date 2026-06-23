package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxService(outboxEventRepository, objectMapper);
    }

    @Nested
    class AppendEvent {

        @Test
        void appendEvent_shouldWriteEventWithVersionOne_whenNoExistingEvents() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001");

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            OutboxEvent saved = captor.getValue();

            assertThat(saved.getAggregateVersion()).isEqualTo(1L);
            assertThat(saved.getAggregateId()).isEqualTo("Imports.Notification.GBN-AG.GBN-AG-26-ABC123");
            assertThat(saved.getAggregateType()).isEqualTo("Notification");
            assertThat(saved.getSubType()).isEqualTo("GBN-AG");
            assertThat(saved.getEventType()).isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
            assertThat(saved.getMetadata().getCorrelationId()).isEqualTo("trace-001");
            assertThat(saved.getMetadata().getSchemaVersion()).isEqualTo("1");
            assertThat(saved.getEventId()).isNotNull();
            assertThat(saved.getTimestamp()).isNotNull();
        }

        @Test
        void appendEvent_shouldIncrementVersion_whenPriorEventsExist() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            OutboxEvent existing = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ABC123")
                .aggregateVersion(3L)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(Optional.of(existing));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-002");

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(4L);
        }

        @Test
        void appendEvent_shouldIncludeAllNotificationDataInEvent() {
            // Given
            Origin origin = new Origin("GB", "true", "REF123");
            Commodity commodity = Commodity.builder().name("Live bovine animals").build();
            AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
            Transport transport = Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, 4, 22))
                .build();

            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(NotificationStatus.SUBMITTED)
                .origin(origin)
                .commodity(commodity)
                .reasonForImport("PERMANENT")
                .additionalDetails(additionalDetails)
                .cphNumber("12/345/6789")
                .transport(transport)
                .consignor(NotificationTestData.consignors().getFirst())
                .destination(NotificationTestData.destinations().getFirst())
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001");

            // Then — data is stored as Map<String, Object> (opaque JSON, schema-agnostic)
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            Map<String, Object> data = captor.getValue().getData();
            assertThat(data).containsKey("referenceNumber");
            assertThat(data.get("referenceNumber")).isEqualTo("GBN-AG-26-ABC123");
            assertThat(data).containsKey("origin");
            assertThat(data).containsKey("commodity");
            assertThat(data.get("reasonForImport")).isEqualTo("PERMANENT");
            assertThat(data).containsKey("additionalDetails");
            assertThat(data.get("cphNumber")).isEqualTo("12/345/6789");
            assertThat(data).containsKey("transport");
            assertThat(data).containsKey("consignor");
            assertThat(data).containsKey("destination");
        }

        @Test
        void appendEvent_shouldStoreEventTypeFromArgument_whenAmendType() {
            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-AMD009")
                .status(NotificationStatus.AMEND)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-AMD009"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.appendEvent(
                notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-9");

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventType())
                .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmissionAmended");
        }

        @Test
        void appendEvent_shouldIncrementFromHighestVersion_whenPriorEventsExistForAmendedNotification() {
            // Regression for the EUDPA-171 amend flow: a notification can have
            // more than one outbox event (initial submit, then amend, etc.). The
            // derived findTopBy…OrderBy…Desc method returns the single highest
            // version (or empty); appendEvent must compute nextVersion from it
            // without exception.
            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-AMD007")
                .status(NotificationStatus.SUBMITTED)
                .build();

            OutboxEvent latest = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-AMD007")
                .aggregateVersion(2L)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-AMD007"))
                .thenReturn(Optional.of(latest));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-7");

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3L);
        }

        @Test
        void appendEvent_shouldThrowOutboxWriteException_onDuplicateKey() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any()))
                .thenThrow(new DuplicateKeyException("duplicate key"));

            // When / Then
            assertThatThrownBy(() -> outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001"))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG.GBN-AG-26-ABC123");
                    assertThat(owe.getAggregateVersion()).isEqualTo(1L);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });
        }
    }

    @Nested
    class FindByReferenceNumber {

        @Test
        void findByReferenceNumber_shouldReturnEventsInAggregateVersionOrder() {
            // Given
            OutboxEvent v1 = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ABC123")
                .aggregateVersion(1L)
                .build();
            OutboxEvent v2 = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ABC123")
                .aggregateVersion(2L)
                .build();

            when(outboxEventRepository.findAllByAggregateIdOrderByAggregateVersionAsc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(List.of(v1, v2));

            // When
            List<OutboxEvent> result = outboxService.findByReferenceNumber("GBN-AG-26-ABC123");

            // Then
            assertThat(result).containsExactly(v1, v2);
        }

        @Test
        void findByReferenceNumber_shouldReturnEmptyList_whenNoEventsExist() {
            // Given
            when(outboxEventRepository.findAllByAggregateIdOrderByAggregateVersionAsc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABSENT"))
                .thenReturn(List.of());

            // When
            List<OutboxEvent> result = outboxService.findByReferenceNumber("GBN-AG-26-ABSENT");

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class BuildAggregateId {

        @Test
        void buildAggregateId_shouldPrefixReferenceNumber() {
            assertThat(OutboxService.buildAggregateId("GBN-AG-26-ABC123"))
                .isEqualTo("Imports.Notification.GBN-AG.GBN-AG-26-ABC123");
        }
    }
}
