package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Commodity {
    
    private String name;
    
    private List<CommodityComplement> commodityComplement;

}
