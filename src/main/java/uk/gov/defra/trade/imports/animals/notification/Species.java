package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Species {
    
    private String value;
    private String text;
    private Integer noOfAnimals;
    private Integer noOfPackages;
    private String earTag;
    private String passport;

}
