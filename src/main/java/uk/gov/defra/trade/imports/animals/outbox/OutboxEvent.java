package uk.gov.defra.trade.imports.animals.outbox;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "outbox")
@CompoundIndex(name = "aggregate_version_uq",
    def = "{'aggregateId': 1, 'aggregateVersion': 1}",
    unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    private String id;

    @Indexed
    private String aggregateId;

    private String aggregateType;
    private String subType;
    private long aggregateVersion;
    private String eventType;
    private Instant timestamp;
    private NotificationSubmittedData data;
    private OutboxEventMetadata metadata;
}
