package uk.gov.defra.trade.imports.animals.outbox;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.notification.Notification;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

    static final String AGGREGATE_TYPE = "Notification";
    static final String SUB_TYPE = "GBN-AG";
    static final String EVENT_TYPE = "uk.gov.defra.imports.notification.NotificationSubmitted";
    static final String SCHEMA_VERSION = "1";
    static final String AGGREGATE_ID_PREFIX = "Imports.Notification.GBN-AG.";

    private final OutboxEventRepository outboxEventRepository;

    public void appendEvent(Notification notification, String correlationId) {
        String aggregateId = buildAggregateId(notification.getReferenceNumber());

        long nextVersion = outboxEventRepository
            .findTopByAggregateIdOrderByAggregateVersionDesc(aggregateId)
            .map(e -> e.getAggregateVersion() + 1)
            .orElse(1L);

        OutboxEvent event = OutboxEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .aggregateId(aggregateId)
            .aggregateType(AGGREGATE_TYPE)
            .subType(SUB_TYPE)
            .aggregateVersion(nextVersion)
            .eventType(EVENT_TYPE)
            .timestamp(Instant.now())
            .data(NotificationSubmittedData.from(notification))
            .metadata(OutboxEventMetadata.builder()
                .correlationId(correlationId)
                .schemaVersion(SCHEMA_VERSION)
                .build())
            .build();

        try {
            outboxEventRepository.save(event);
        } catch (DuplicateKeyException e) {
            throw new OutboxWriteException(
                "Duplicate aggregateVersion on outbox insert",
                aggregateId, nextVersion, correlationId, e);
        }

        log.info("Outbox event written: aggregateId={} version={} correlationId={}",
            aggregateId, nextVersion, correlationId);
    }

    public static String buildAggregateId(String referenceNumber) {
        return AGGREGATE_ID_PREFIX + referenceNumber;
    }
}
