package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when a requested resource is not found.
 * Will be mapped to 404 Not Found by GlobalExceptionHandler.
 */
public class TradeImportsAnimalsBackendException extends RuntimeException {

    public TradeImportsAnimalsBackendException(String message) {
        super(message);
    }

    public TradeImportsAnimalsBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
