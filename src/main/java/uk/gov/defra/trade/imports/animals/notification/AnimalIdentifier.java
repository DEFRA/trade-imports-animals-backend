package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One animal-identifier unit on a {@link Species} line. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalIdentifier {

    private String microchip;
    private String passport;
    private String tattoo;
    private String earTag;
    private String horseName;
    private String identificationDetails;
    private String description;
    private ConsignmentParty permanentAddress;
}
