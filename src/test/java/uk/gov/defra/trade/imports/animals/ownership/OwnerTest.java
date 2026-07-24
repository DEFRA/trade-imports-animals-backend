package uk.gov.defra.trade.imports.animals.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OwnerTest {

    @Test
    void constructor_shouldPreserveEmptyOrganisation() {
        Owner owner = new Owner("defra-sub", "");

        assertThat(owner.organisation()).isEmpty();
        assertThat(owner).isEqualTo(new Owner("defra-sub", ""));
        assertThat(owner).isNotEqualTo(new Owner("defra-sub", "organisation"));
    }

    @Test
    void constructor_shouldRejectNullOrBlankIdentityParts() {
        assertThatThrownBy(() -> new Owner(null, "organisation"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Owner(" ", "organisation"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Owner("defra-sub", null))
            .isInstanceOf(NullPointerException.class);
    }
}
