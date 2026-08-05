package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Exception thrown when a valid request cannot be processed with its supplied semantics.
 * Mapped to 422 Unprocessable Entity by {@link GlobalExceptionHandler}.
 */
public class UnprocessableEntityException extends TradeImportsAnimalsBackendException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
