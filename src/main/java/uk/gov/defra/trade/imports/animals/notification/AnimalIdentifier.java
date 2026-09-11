package uk.gov.defra.trade.imports.animals.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One animal-identifier unit on a {@link Species} line — every unit, not just the first. */
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
    private ConsignmentParty permanentAddress;
}
