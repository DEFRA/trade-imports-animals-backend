package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Species;

public record TradeLineItem(
    List<ApplicableClassification> applicableClassification,
    List<String> description,
    String scientificName,
    String commonName,
    List<LineTradeDelivery> specifiedLineTradeDelivery,
    List<LogisticsPackage> physicalReferencedLogisticsPackage,
    List<TradeProductInstance> individualTradeProductInstance
) {

    static List<TradeLineItem> linesFrom(Commodity entry) {
        List<Species> species = entry.getSpecies();
        if (species == null || species.isEmpty()) {
            return List.of(fromComplementTotals(entry));
        }
        return species.stream()
            .map(s -> fromSpecies(entry, s))
            .toList();
    }

    private static TradeLineItem fromSpecies(Commodity entry, Species species) {
        return new TradeLineItem(
            classificationOf(entry),
            descriptionOf(entry.getDescription()),
            species.getText(),
            tradeLineCommonName(entry, species),
            LineTradeDelivery.headCount(species.getNoOfAnimals()),
            LogisticsPackage.packageCount(species.getNoOfPackages()),
            TradeProductInstance.instancesFrom(species));
    }

    private static TradeLineItem fromComplementTotals(Commodity entry) {
        return new TradeLineItem(
            classificationOf(entry),
            descriptionOf(entry.getDescription()),
            null,
            textOrNull(entry.getName()),
            LineTradeDelivery.headCount(entry.getTotalNoOfAnimals()),
            LogisticsPackage.packageCount(entry.getTotalNoOfPackages()),
            null);
    }

    @SuppressWarnings("java:S1168")
    private static List<ApplicableClassification> classificationOf(Commodity entry) {
        String cn = entry.getCommodityCode();
        if (cn == null || cn.isBlank()) {
            return null;
        }
        return List.of(ApplicableClassification.cn(cn));
    }

    private static String tradeLineCommonName(Commodity entry, Species species) {
        String fromSpecies = commonNameOf(species);
        if (fromSpecies != null) {
            return fromSpecies;
        }
        return textOrNull(entry.getName());
    }

    private static String textOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String commonNameOf(Species species) {
        String commonName = species.getCommonName();
        if (commonName == null || commonName.isBlank()) {
            return null;
        }
        return commonName;
    }

    @SuppressWarnings("java:S1168")
    private static List<String> descriptionOf(String commodityDescription) {
        if (commodityDescription == null || commodityDescription.isBlank()) {
            return null;
        }
        return List.of(commodityDescription);
    }
}
