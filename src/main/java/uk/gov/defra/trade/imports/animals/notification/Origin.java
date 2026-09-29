package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Origin {

    private String countryCode;
    private String requiresRegionCode;
    private String internalReference;
    private String regionOfOriginCode;
    private String countrySubdivisionCode;

    /** Preserves the pre-subdivision four-argument call sites across the codebase. */
    public Origin(
        String countryCode,
        String requiresRegionCode,
        String internalReference,
        String regionOfOriginCode
    ) {
        this.countryCode = countryCode;
        this.requiresRegionCode = requiresRegionCode;
        this.internalReference = internalReference;
        this.regionOfOriginCode = regionOfOriginCode;
    }
}
