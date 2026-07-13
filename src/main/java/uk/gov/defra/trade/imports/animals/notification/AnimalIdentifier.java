package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalIdentifier {

    private String passport;
    private String tattoo;
    private String earTag;
    private String horseName;
    private String identificationDetails;
    private String description;
    private Address permanentAddress;

}
