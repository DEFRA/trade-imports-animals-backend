package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * Caller is authenticated and scoped, but not permitted to access the resource (403).
 */
public class ForbiddenException extends TradeImportsAnimalsBackendException {

    public ForbiddenException(String message) {
        super(message);
    }
}
