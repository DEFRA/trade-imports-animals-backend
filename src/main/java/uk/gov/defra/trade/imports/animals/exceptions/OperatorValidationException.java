package uk.gov.defra.trade.imports.animals.exceptions;

import java.util.List;
import java.util.Map;

/**
 * Thrown by the submit guard when a referenced operator fails its existence check (design §4.5,
 * c-018): a tombstoned (DELETED) or unresolved (404) operator must never be frozen into a SUBMITTED
 * record. Carries an {@code errors} map keyed by the notification party field, so
 * {@code GlobalExceptionHandler} renders a 400 validation-error problem in the same shape as a
 * bean-validation failure. Deleted and unresolved keep distinct messages — a 404 is not a deletion.
 */
public class OperatorValidationException extends TradeImportsAnimalsBackendException {

    private final transient Map<String, List<String>> errors;

    public OperatorValidationException(Map<String, List<String>> errors) {
        super("Operator verification failed for one or more parties");
        this.errors = errors;
    }

    public Map<String, List<String>> getErrors() {
        return errors;
    }
}
