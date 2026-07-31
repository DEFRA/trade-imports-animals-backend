package uk.gov.defra.trade.imports.plantproducts.notification;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CommodityLine {

    private String uniqueComplementId;
    private String commodityCode;
    private String commodityDescription;
    private Integer numberOfPackages;
    private String packageType;
    private BigDecimal quantity;
    private String quantityType;
    private BigDecimal netWeight;
    private Boolean controlledAtmosphereContainer;
    private FinishedOrPropagated finishedOrPropagated;
    private Boolean intendedForFinalUsers;
    private Boolean testAndTrial;
    private List<PlantSpecies> species;
}
