package uk.gov.defra.trade.imports.animals.ownership;

import java.util.Objects;

/**
 * Identifies the Defra ID user and organisation that own a journey.
 */
public record Owner(String sub, String organisation) {

    public Owner {
        Objects.requireNonNull(sub, "sub must not be null");
        Objects.requireNonNull(organisation, "organisation must not be null");
        if (sub.isBlank()) {
            throw new IllegalArgumentException("sub must not be blank");
        }
    }
}
