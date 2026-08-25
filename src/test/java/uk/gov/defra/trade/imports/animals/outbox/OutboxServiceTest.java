package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.Month;
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
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.gbnag.GbnAgEventDataMapper;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxService(outboxEventRepository, objectMapper, new GbnAgEventDataMapper());
    }

    @Nested
    class AppendEvent {

        @Test
        void appendEvent_shouldWriteEventWithVersionOne_whenNoExistingEvents() {
            // Given
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null);

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
            assertThat(saved.getMetadata().getSchemaUrl()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl());
            assertThat(saved.getEventId()).isNotNull();
            assertThat(saved.getTimestamp()).isNotNull();
            assertThat(saved.getActor()).isNull();
            assertThat(saved.getStatusChanges()).hasSize(1);
            assertThat(saved.getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(saved.getStatusChanges().getFirst().getActor()).isNull();
        }

        @Test
        void appendEvent_shouldIncrementVersion_whenPriorEventsExist() {
            // Given
            NotificationAggregate notification = NotificationAggregate.builder()
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
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-002", null);

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(4L);
        }

        @Test
        @SuppressWarnings("unchecked")
        void appendEvent_shouldStoreGbnAgPayloadInDataField() {
            // Given
            Origin origin = new Origin("GB", "true", "REF123");
            Commodity commodity = Commodity.builder().name("Live bovine animals").build();
            AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
            Transport transport = Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .build();

            NotificationAggregate notification = NotificationAggregate.builder()
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
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null);

            // Then — data is the GBN-AG payload (mapped from the NotificationAggregate), stored as
            // Map<String, Object>. Field-level mapping is covered by GbnAgMapperTest; here we
            // assert the service stores the GBN-AG shape rather than the raw notification.
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            Map<String, Object> data = captor.getValue().getData();
            assertThat(data)
                .containsKeys("$model", "$type", "exchangedDocument", "specifiedConsignment")
                .containsEntry("$type", "gbn-ag")
                .doesNotContainKey("referenceNumber");

            Map<String, Object> exchangedDocument = (Map<String, Object>) data.get("exchangedDocument");
            assertThat(exchangedDocument)
                .containsEntry("identifier", "GBN-AG-26-ABC123")
                .containsEntry("notificationStatusCode", "SUBMITTED");

            Map<String, Object> specifiedConsignment =
                (Map<String, Object>) data.get("specifiedConsignment");
            assertThat(specifiedConsignment).containsKeys("consignorParty", "deliveryParty");
        }

        @Test
        void appendEvent_shouldStoreEventTypeFromArgument_whenAmendType() {
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-AMD009")
                .status(NotificationStatus.AMEND)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-AMD009"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxService.appendEvent(
                notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-9", null);

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
            NotificationAggregate notification = NotificationAggregate.builder()
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

            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-amd-7", null);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3L);
        }

        @Test
        void appendEvent_shouldStampActorOnEvent_whenActorProvided() {
            // Given
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-ACT001")
                .status(NotificationStatus.SUBMITTED)
                .build();
            Actor actor = Actor.builder()
                .id("contact-guid-001")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("Jane Farmer")
                .organisationId("org-001")
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ACT001"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-act-1", actor);

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            OutboxEvent saved = captor.getValue();

            assertThat(saved.getActor()).isEqualTo(actor);
            assertThat(saved.getStatusChanges()).hasSize(1);
            StatusChange change = saved.getStatusChanges().getFirst();
            assertThat(change.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(change.getDateChanged()).isNotNull();
            assertThat(change.getActor()).isEqualTo(actor);
        }

        @Test
        void appendEvent_shouldBuildCumulativeStatusChanges_fromLatestPriorEvent() {
            // Given — latest prior event already has a SUBMITTED statusChange
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-ACT002")
                .status(NotificationStatus.AMEND)
                .build();
            Actor submitActor = Actor.builder()
                .id("contact-guid-001")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("Jane Farmer")
                .organisationId("org-001")
                .build();
            Actor amendActor = Actor.builder()
                .id("contact-guid-002")
                .source("dynamics-contact")
                .userType("B2C")
                .displayName("John Agent")
                .organisationId("org-002")
                .build();
            StatusChange priorChange = StatusChange.builder()
                .status(NotificationStatus.SUBMITTED)
                .dateChanged(java.time.Instant.parse("2026-01-01T10:00:00Z"))
                .actor(submitActor)
                .build();
            OutboxEvent latestEvent = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-ACT002")
                .aggregateVersion(1L)
                .statusChanges(List.of(priorChange))
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ACT002"))
                .thenReturn(Optional.of(latestEvent));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(
                notification, OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED, "trace-act-2", amendActor);

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            OutboxEvent saved = captor.getValue();

            assertThat(saved.getStatusChanges()).hasSize(2);
            assertThat(saved.getStatusChanges().get(0).getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
            assertThat(saved.getStatusChanges().get(0).getActor()).isEqualTo(submitActor);
            assertThat(saved.getStatusChanges().get(1).getStatus()).isEqualTo(NotificationStatus.AMEND);
            assertThat(saved.getStatusChanges().get(1).getActor()).isEqualTo(amendActor);
        }

        @Test
        void appendEvent_shouldAppendDraftStatusChange_forFirstPageSave() {
            // Given — first-ever event for this notification is a DRAFT page save (no prior events)
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-EDIT01")
                .status(NotificationStatus.DRAFT)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-EDIT01"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(
                notification, OutboxEventType.NOTIFICATION_EDITED, "trace-edit-1", null);

            // Then — DRAFT recorded as the starting state
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getStatusChanges()).hasSize(1);
            assertThat(captor.getValue().getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void appendEvent_shouldNotDuplicateStatusChange_forConsecutivePageSavesWithSameStatus() {
            // Given — second DRAFT page save; prior event already records DRAFT
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-EDIT02")
                .status(NotificationStatus.DRAFT)
                .build();
            StatusChange priorChange = StatusChange.builder()
                .status(NotificationStatus.DRAFT)
                .dateChanged(java.time.Instant.parse("2026-01-01T10:00:00Z"))
                .actor(null)
                .build();
            OutboxEvent latestEvent = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-EDIT02")
                .aggregateVersion(1L)
                .statusChanges(List.of(priorChange))
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-EDIT02"))
                .thenReturn(Optional.of(latestEvent));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(
                notification, OutboxEventType.NOTIFICATION_EDITED, "trace-edit-2", null);

            // Then — statusChanges unchanged; DRAFT already recorded
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getStatusChanges()).hasSize(1);
            assertThat(captor.getValue().getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.DRAFT);
        }

        @Test
        void appendEvent_shouldNotDuplicateStatusChange_forAmendPhasePageSave() {
            // Given — AMEND phase page save; prior event already records AMEND
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-EDIT03")
                .status(NotificationStatus.AMEND)
                .build();
            StatusChange priorChange = StatusChange.builder()
                .status(NotificationStatus.AMEND)
                .dateChanged(java.time.Instant.parse("2026-01-01T11:00:00Z"))
                .actor(null)
                .build();
            OutboxEvent latestEvent = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.GBN-AG-26-EDIT03")
                .aggregateVersion(2L)
                .statusChanges(List.of(priorChange))
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-EDIT03"))
                .thenReturn(Optional.of(latestEvent));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(
                notification, OutboxEventType.NOTIFICATION_EDITED, "trace-edit-3", null);

            // Then — statusChanges unchanged; AMEND already recorded
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getStatusChanges()).hasSize(1);
            assertThat(captor.getValue().getStatusChanges().getFirst().getStatus()).isEqualTo(NotificationStatus.AMEND);
        }

        @Test
        void appendEvent_shouldThrowOutboxWriteException_onDuplicateKey() {
            // Given
            NotificationAggregate notification = NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-ABC123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(
                "Imports.Notification.GBN-AG.GBN-AG-26-ABC123"))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any()))
                .thenThrow(new DuplicateKeyException("duplicate key"));

            // When / Then
            assertThatThrownBy(() -> outboxService.appendEvent(notification, OutboxEventType.NOTIFICATION_SUBMITTED, "trace-001", null))
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
