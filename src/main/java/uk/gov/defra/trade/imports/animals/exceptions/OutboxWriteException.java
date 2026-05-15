package uk.gov.defra.trade.imports.animals.exceptions;

import lombok.Getter;

@Getter
public class OutboxWriteException extends RuntimeException {

    private final String aggregateId;
    private final Long aggregateVersion;
    private final String correlationId;

    public OutboxWriteException(String message, String aggregateId, Long aggregateVersion,
        String correlationId, Throwable cause) {
        super(message, cause);
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.correlationId = correlationId;
    }

    public OutboxWriteException(String message, String aggregateId, Long aggregateVersion,
        String correlationId) {
        super(message);
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.correlationId = correlationId;
    }
}
