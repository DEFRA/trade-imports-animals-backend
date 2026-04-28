package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when a requested resource is not found.
 * Will be mapped to 404 Not Found by GlobalExceptionHandler.
 */
public class NotFoundException extends TradeImportsAnimalsBackendException {

    public NotFoundException(String message) {
        super(message);
    }
}
