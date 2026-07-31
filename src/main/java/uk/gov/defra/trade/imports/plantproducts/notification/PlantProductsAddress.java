package uk.gov.defra.trade.imports.plantproducts.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsAddress {

    private String addressLine1;
    private String addressLine2;
    private String addressLine3;
    private String city;
    private String postcode;
    private String country;
}
