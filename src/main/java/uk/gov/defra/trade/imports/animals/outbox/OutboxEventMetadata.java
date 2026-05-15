package uk.gov.defra.trade.imports.animals.outbox;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OutboxEventMetadata {

    String correlationId;
    String schemaVersion;
}
