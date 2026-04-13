package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommodityComplement {
    
    private String typeOfCommodity;
    private Integer totalNoOfAnimals;
    private Integer totalNoOfPackages;
    private List<Species> species;

}
