package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.Species;

public record TradeLineItem(
    List<ApplicableClassification> applicableClassification,
    List<String> description,
    String scientificName,
    String commonName,
    String typeCode,
    String urlId,
    List<LineTradeDelivery> specifiedLineTradeDelivery,
    List<LogisticsPackage> physicalReferencedLogisticsPackage,
    List<TradeProductInstance> individualTradeProductInstance
) {

    /**
     * One line per species on the complement, each carrying that species' own counts. A complement
     * with no species still gets one line, from the complement totals.
     */
    static List<TradeLineItem> linesFrom(CommodityComplement entry) {
        List<Species> species = entry.getSpecies();
        if (species == null || species.isEmpty()) {
            return List.of(fromComplementTotals(entry));
        }
        return species.stream()
            .map(s -> fromSpecies(entry, s))
            .toList();
    }

    private static TradeLineItem fromSpecies(CommodityComplement entry, Species species) {
        return new TradeLineItem(
            classificationOf(entry),
            descriptionOf(entry.getDescription()),
            species.getText(),
            commonNameOf(species),
            typeCodeFor(species, entry),
            null,
            LineTradeDelivery.headCount(species.getNoOfAnimals()),
            LogisticsPackage.packageCount(species.getNoOfPackages()),
            TradeProductInstance.instancesFrom(species));
    }

    private static TradeLineItem fromComplementTotals(CommodityComplement entry) {
        return new TradeLineItem(
            classificationOf(entry),
            descriptionOf(entry.getDescription()),
            null,
            null,
            entry.getTypeOfCommodity(),
            null,
            LineTradeDelivery.headCount(entry.getTotalNoOfAnimals()),
            LogisticsPackage.packageCount(entry.getTotalNoOfPackages()),
            null);
    }

    private static String typeCodeFor(Species species, CommodityComplement entry) {
        if (species.getTypeOfCommodity() != null && !species.getTypeOfCommodity().isBlank()) {
            return species.getTypeOfCommodity();
        }
        return entry.getTypeOfCommodity();
    }

    @SuppressWarnings("java:S1168")
    private static List<ApplicableClassification> classificationOf(CommodityComplement entry) {
        String cn = entry.getCommodityCode();
        if (cn == null || cn.isBlank()) {
            return null;
        }
        return List.of(ApplicableClassification.cn(cn));
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
