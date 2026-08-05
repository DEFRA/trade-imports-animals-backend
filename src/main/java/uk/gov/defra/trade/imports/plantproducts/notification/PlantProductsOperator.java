package uk.gov.defra.trade.imports.plantproducts.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsOperator {

    private String operatorId;
    private String name;
    private String telephone;
    private String email;
    private PlantProductsAddress address;
}
