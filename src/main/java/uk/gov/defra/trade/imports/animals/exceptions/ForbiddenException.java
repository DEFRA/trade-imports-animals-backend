package uk.gov.defra.trade.imports.animals.exceptions;

/**
 * The caller is authenticated but not permitted to access the resource (403). Raised when a
 * trader-scoped resource exists under another {@code Trade-Imports-Organisation-Id} than the one
 * on the request.
 */
public class ForbiddenException extends TradeImportsAnimalsBackendException {

    public ForbiddenException(String message) {
        super(message);
    }
}
