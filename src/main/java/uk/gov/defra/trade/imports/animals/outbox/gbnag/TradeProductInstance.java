package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.ArrayList;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Species;

public record TradeProductInstance(
    String name,
    List<AnimalIdentifier> identifier,
    TradeParty permanentLocation
) {

    public TradeProductInstance {
        identifier = identifier == null ? null : List.copyOf(identifier);
    }

    static final String ANIMAL_IDENTIFIER_TYPES = "https://codelists.tbc.defra.gov.uk/animal_identifier_types";

    private static final String EAR_TAG = "EAR_TAG";
    private static final String PASSPORT = "PASSPORT";
    private static final String MICROCHIP = "MICROCHIP";
    private static final String TATTOO = "TATTOO";

    public record AnimalIdentifier(String typeCode, String content, String urlId) {}

    static List<TradeProductInstance> instancesFrom(Species species) {
        var animals = species.getAnimalIdentifiers();
        if (animals == null || animals.isEmpty()) {
            return List.of(new TradeProductInstance(
                null,
                identifiersOf(species.getEarTag(), species.getPassport(), species.getMicrochip(), null),
                null));
        }
        return animals.stream()
            .map(animal -> new TradeProductInstance(
                animal.getHorseName(),
                identifiersOf(animal.getEarTag(), animal.getPassport(), animal.getMicrochip(), animal.getTattoo()),
                TradeParty.from(animal.getPermanentAddress())))
            .toList();
    }

    @SuppressWarnings("java:S1168")
    private static List<AnimalIdentifier> identifiersOf(
        String earTag, String passport, String microchip, String tattoo) {
        List<AnimalIdentifier> identifiers = new ArrayList<>();
        addIfPresent(identifiers, EAR_TAG, earTag);
        addIfPresent(identifiers, PASSPORT, passport);
        addIfPresent(identifiers, MICROCHIP, microchip);
        addIfPresent(identifiers, TATTOO, tattoo);
        return identifiers.isEmpty() ? null : identifiers;
    }

    private static void addIfPresent(List<AnimalIdentifier> identifiers, String typeCode, String content) {
        if (content != null && !content.isBlank()) {
            identifiers.add(new AnimalIdentifier(typeCode, content, ANIMAL_IDENTIFIER_TYPES));
        }
    }
}
