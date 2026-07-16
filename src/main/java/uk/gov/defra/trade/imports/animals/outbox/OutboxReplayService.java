package uk.gov.defra.trade.imports.animals.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.AuditContext;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxReplayService {

    private final OutboxService outboxService;
    private final OutboxPublishService outboxPublishService;
    private final AuditRepository auditRepository;
    private final OutboxConfig outboxConfig;

    public int replay(String referenceNumber, AuditContext auditContext) {
        List<OutboxEvent> events = outboxService.findByReferenceNumber(referenceNumber);
        if (events.isEmpty()) {
            throw new NotFoundException("No outbox events found for: " + referenceNumber);
        }

        String topicArn = outboxConfig.sns().topicArn();
        boolean failed = false;
        for (OutboxEvent event : events) {
            try {
                outboxPublishService.publishToSns(event, topicArn);
            } catch (JsonProcessingException e) {
                log.error(
                    "Outbox event payload is not serializable; manual investigation required: "
                        + "eventId={} aggregateId={} version={}",
                    event.getEventId(), event.getAggregateId(), event.getAggregateVersion(), e);
                failed = true;
                break;
            }
        }

        writeAuditRecord(referenceNumber, events.size(), auditContext, failed ? Result.FAILURE : Result.SUCCESS);
        log.info("Replayed {} event(s) for referenceNumber={} by userId={}",
            events.size(), referenceNumber, auditContext.userId());
        return events.size();
    }

    private void writeAuditRecord(String referenceNumber, int count, AuditContext auditContext, Result result) {
        auditRepository.save(Audit.builder()
            .action(Action.REPLAY_EVENTS)
            .result(result)
            .notificationReferenceNumbers(List.of(referenceNumber))
            .numberOfNotifications(1)
            .numberOfEvents(count)
            .traceId(auditContext.traceId())
            .userId(auditContext.userId())
            .timestamp(LocalDateTime.now())
            .build());
    }
}
