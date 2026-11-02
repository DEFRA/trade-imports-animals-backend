package uk.gov.defra.trade.imports.animals.notification;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Commodity {

    private String name;

    private String commodityCode;

    private String description;

    private String typeOfCommodity;
    private Integer totalNoOfAnimals;
    private Integer totalNoOfPackages;
    @Builder.Default
    private List<Species> species = new ArrayList<>();
}
