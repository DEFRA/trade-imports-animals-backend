package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Origin {
    
    /** the country of origin for the import. */
    private String countryOfOrigin;
    
    /** whether the consignment requires a region code. */
    private String requiresRegionCode;
    
    /** the customer internal reference. */
    private String internalReference;
    
}
