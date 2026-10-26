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
public class CommodityComplement {

    private String name;

    private String commodityCode;

    private String description;

    private String typeOfCommodity;
    private Integer totalNoOfAnimals;
    private Integer totalNoOfPackages;
    private List<Species> species;

    public CommodityComplement(
        String typeOfCommodity,
        Integer totalNoOfAnimals,
        Integer totalNoOfPackages,
        List<Species> species) {
        this(null, null, null, typeOfCommodity, totalNoOfAnimals, totalNoOfPackages, species);
    }
}
