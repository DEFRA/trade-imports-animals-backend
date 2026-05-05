package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when a resource conflict occurs (e.g., duplicate key).
 * Will be mapped to 409 Conflict by GlobalExceptionHandler.
 */
public class ConflictException extends TradeImportsAnimalsBackendException {

    public ConflictException(String message) {
        super(message);
    }
}
