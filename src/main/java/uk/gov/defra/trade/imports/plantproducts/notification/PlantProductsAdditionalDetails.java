package uk.gov.defra.trade.imports.plantproducts.notification;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsAdditionalDetails {

    private BigDecimal totalGrossWeight;
    private BigDecimal grossVolume;
    private GrossVolumeUnit grossVolumeUnit;
}
