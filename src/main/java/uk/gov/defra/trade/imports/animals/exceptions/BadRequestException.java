package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when a request is malformed at the application level (after Spring's own
 * validation). Will be mapped to 400 Bad Request by GlobalExceptionHandler.
 */
public class BadRequestException extends TradeImportsAnimalsBackendException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
