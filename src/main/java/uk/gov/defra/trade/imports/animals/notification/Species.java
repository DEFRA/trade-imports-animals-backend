package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Species {
    
    private String value;
    private String text;
    private Integer noOfAnimals;
    private Integer noOfPackages;

}
