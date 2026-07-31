package uk.gov.defra.trade.imports.plantproducts.notification;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlantProductsCommodity {

    private String name;
    private CommodityInputMethod inputMethod;
    private List<CommodityLine> commodityComplement;
}
