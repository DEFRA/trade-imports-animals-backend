package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * General application exception for the Trade Imports Animals backend.
 * Extends {@link RuntimeException} and is caught by the global exception handler,
 * which maps unhandled runtime exceptions to a 500 Internal Server Error response.
 */
public class TradeImportsAnimalsBackendException extends RuntimeException {

    public TradeImportsAnimalsBackendException(String message) {
        super(message);
    }

    public TradeImportsAnimalsBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
