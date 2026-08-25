package uk.gov.defra.trade.imports.animals.exceptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when a submit references address-book records that cannot be resolved — deleted, or
 * belonging to another organisation.
 *
 * <p>Carries every affected role rather than the first one found, so the caller can correct them
 * all in one pass. Iteration order is the fixed role order the resolver walks, so the same
 * notification fails the same way every time.
 */
public class UnresolvableConsignmentPartyException extends BadRequestException {

    private final Map<String, String> addressIdByRole;

    public UnresolvableConsignmentPartyException(Map<String, String> addressIdByRole) {
        super("Cannot submit notification: no address-book record for "
            + String.join(", ", addressIdByRole.keySet()));
        this.addressIdByRole = new LinkedHashMap<>(addressIdByRole);
    }

    public Map<String, String> addressIdByRole() {
        return Collections.unmodifiableMap(addressIdByRole);
    }
}
