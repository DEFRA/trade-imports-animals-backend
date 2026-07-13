package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.model.SnsException;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.AuditContext;

@ExtendWith(MockitoExtension.class)
class OutboxReplayServiceTest {

    private static final String REF = "GBN-AG-26-ABC123";
    private static final String TOPIC_ARN = "arn:aws:sns:eu-west-2:000000000000:test-topic.fifo";
    private static final AuditContext AUDIT_CTX = new AuditContext("trace-001", "user-abc");

    @Mock
    private OutboxService outboxService;

    @Mock
    private OutboxPublishService outboxPublishService;

    @Mock
    private AuditRepository auditRepository;

    private OutboxReplayService outboxReplayService;

    @BeforeEach
    void setUp() {
        OutboxConfig config = new OutboxConfig(
            new OutboxConfig.Poller(2000, 10, null, null, true),
            new OutboxConfig.Sns(TOPIC_ARN));
        outboxReplayService = new OutboxReplayService(
            outboxService, outboxPublishService, auditRepository, config);
    }

    @Nested
    class Replay {

        @Test
        void replay_shouldPublishEventsInOrderAndReturnCount() throws JsonProcessingException {
            OutboxEvent v1 = OutboxEvent.builder().eventId("evt-1").aggregateVersion(1L).build();
            OutboxEvent v2 = OutboxEvent.builder().eventId("evt-2").aggregateVersion(2L).build();
            when(outboxService.findByReferenceNumber(REF)).thenReturn(List.of(v1, v2));

            int result = outboxReplayService.replay(REF, AUDIT_CTX);

            assertThat(result).isEqualTo(2);
            InOrder order = inOrder(outboxPublishService);
            order.verify(outboxPublishService).publishToSns(v1, TOPIC_ARN);
            order.verify(outboxPublishService).publishToSns(v2, TOPIC_ARN);
        }

        @Test
        void replay_shouldWriteAuditRecordAfterSuccessfulPublish() throws JsonProcessingException {
            OutboxEvent v1 = OutboxEvent.builder().eventId("evt-1").aggregateVersion(1L).build();
            when(outboxService.findByReferenceNumber(REF)).thenReturn(List.of(v1));
            when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            outboxReplayService.replay(REF, AUDIT_CTX);

            ArgumentCaptor<Audit> captor = ArgumentCaptor.forClass(Audit.class);
            verify(auditRepository).save(captor.capture());
            Audit audit = captor.getValue();
            assertThat(audit.getAction()).isEqualTo(Action.REPLAY_EVENTS);
            assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
            assertThat(audit.getNotificationReferenceNumbers()).containsExactly(REF);
            assertThat(audit.getNumberOfNotifications()).isEqualTo(1);
            assertThat(audit.getTraceId()).isEqualTo("trace-001");
            assertThat(audit.getUserId()).isEqualTo("user-abc");
            assertThat(audit.getTimestamp()).isNotNull();
        }

        @Test
        void replay_shouldThrowNotFoundException_whenNoEventsExist() {
            when(outboxService.findByReferenceNumber(REF)).thenReturn(List.of());

            assertThatThrownBy(() -> outboxReplayService.replay(REF, AUDIT_CTX))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(REF);

            verify(outboxPublishService, never()).publishToSns(any(), any());
            verify(auditRepository, never()).save(any());
        }

        @Test
        void replay_shouldPropagateException_whenSnsPublishFails() throws JsonProcessingException {
            OutboxEvent v1 = OutboxEvent.builder().eventId("evt-1").aggregateVersion(1L).build();
            when(outboxService.findByReferenceNumber(REF)).thenReturn(List.of(v1));
            doThrow(SnsException.builder().message("SNS unavailable").build())
                .when(outboxPublishService).publishToSns(eq(v1), eq(TOPIC_ARN));

            assertThatThrownBy(() -> outboxReplayService.replay(REF, AUDIT_CTX))
                .isInstanceOf(SnsException.class);

            verify(auditRepository, never()).save(any());
        }
    }
}
