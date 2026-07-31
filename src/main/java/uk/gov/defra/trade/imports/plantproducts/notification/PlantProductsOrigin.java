package uk.gov.defra.trade.imports.plantproducts.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsOrigin {

    private String countryCode;
    private String countryOfConsignmentCode;
    private String internalReference;
}
