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

    private static final String EAR_TAG = "EAR_TAG";
    private static final String PASSPORT = "PASSPORT";

    public record AnimalIdentifier(String typeCode, String content, String urlId) {}

    @SuppressWarnings("java:S1168")
    static List<TradeProductInstance> instancesFrom(List<Species> species) {
        if (species == null) {
            return null;
        }
        return species.stream().map(TradeProductInstance::fromSpecies).toList();
    }

    private static TradeProductInstance fromSpecies(Species species) {
        List<AnimalIdentifier> identifiers = new ArrayList<>();
        if (species.getEarTag() != null) {
            identifiers.add(new AnimalIdentifier(EAR_TAG, species.getEarTag(), null));
        }
        if (species.getPassport() != null) {
            identifiers.add(new AnimalIdentifier(PASSPORT, species.getPassport(), null));
        }
        return new TradeProductInstance(null, identifiers.isEmpty() ? null : identifiers, null);
    }
}
