package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when an upstream service returns a non-2xx response.
 * Will be mapped to 502 Bad Gateway by GlobalExceptionHandler.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
