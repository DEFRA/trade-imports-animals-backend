package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxEventRepository);
    }

    @Nested
    class AppendEvent {

        @Test
        void appendEvent_shouldWriteEventWithVersionOne_whenNoExistingEvents() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("DRAFT.IMP.2026.abc123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(any()))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, "trace-001");

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            OutboxEvent saved = captor.getValue();

            assertThat(saved.getAggregateVersion()).isEqualTo(1L);
            assertThat(saved.getAggregateId()).isEqualTo("Imports.Notification.GBN-AG.DRAFT.IMP.2026.abc123");
            assertThat(saved.getAggregateType()).isEqualTo("Notification");
            assertThat(saved.getSubType()).isEqualTo("GBN-AG");
            assertThat(saved.getEventType()).isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
            assertThat(saved.getMetadata().getCorrelationId()).isEqualTo("trace-001");
            assertThat(saved.getMetadata().getSchemaVersion()).isEqualTo("1");
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getTimestamp()).isNotNull();
        }

        @Test
        void appendEvent_shouldIncrementVersion_whenPriorEventsExist() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("DRAFT.IMP.2026.abc123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            OutboxEvent existing = OutboxEvent.builder()
                .aggregateId("Imports.Notification.GBN-AG.DRAFT.IMP.2026.abc123")
                .aggregateVersion(3L)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(any()))
                .thenReturn(Optional.of(existing));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, "trace-002");

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(4L);
        }

        @Test
        void appendEvent_shouldIncludeNotificationDataInEvent() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("DRAFT.IMP.2026.abc123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(any()))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxService.appendEvent(notification, "trace-001");

            // Then
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            assertThat(captor.getValue().getData().referenceNumber())
                .isEqualTo("DRAFT.IMP.2026.abc123");
        }

        @Test
        void appendEvent_shouldThrowOutboxWriteException_onDuplicateKey() {
            // Given
            Notification notification = Notification.builder()
                .referenceNumber("DRAFT.IMP.2026.abc123")
                .status(NotificationStatus.SUBMITTED)
                .build();

            when(outboxEventRepository.findTopByAggregateIdOrderByAggregateVersionDesc(any()))
                .thenReturn(Optional.empty());
            when(outboxEventRepository.save(any()))
                .thenThrow(new DuplicateKeyException("duplicate key"));

            // When / Then
            assertThatThrownBy(() -> outboxService.appendEvent(notification, "trace-001"))
                .isInstanceOf(OutboxWriteException.class)
                .satisfies(ex -> {
                    OutboxWriteException owe = (OutboxWriteException) ex;
                    assertThat(owe.getAggregateId())
                        .isEqualTo("Imports.Notification.GBN-AG.DRAFT.IMP.2026.abc123");
                    assertThat(owe.getAggregateVersion()).isEqualTo(1L);
                    assertThat(owe.getCorrelationId()).isEqualTo("trace-001");
                });
        }
    }

    @Nested
    class BuildAggregateId {

        @Test
        void buildAggregateId_shouldPrefixReferenceNumber() {
            assertThat(OutboxService.buildAggregateId("DRAFT.IMP.2026.abc123"))
                .isEqualTo("Imports.Notification.GBN-AG.DRAFT.IMP.2026.abc123");
        }
    }
}
