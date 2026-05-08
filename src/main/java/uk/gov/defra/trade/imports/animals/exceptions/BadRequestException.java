package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when application-level validation rejects a request payload (for example, a
 * callback payload missing required metadata). Mapped to 400 Bad Request by
 * GlobalExceptionHandler.
 */
public class BadRequestException extends TradeImportsAnimalsBackendException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
