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
    static List<TradeLineItem> linesFrom(String commodityName, CommodityComplement complement) {
        List<Species> species = complement.getSpecies();
        if (species == null || species.isEmpty()) {
            return List.of(fromComplementTotals(commodityName, complement));
        }
        return species.stream()
            .map(s -> fromSpecies(commodityName, complement, s))
            .toList();
    }

    private static TradeLineItem fromSpecies(String commodityName, CommodityComplement complement, Species species) {
        return new TradeLineItem(
            classificationOf(complement),
            descriptionOf(commodityName),
            species.getText(),
            commodityName,
            null,
            null,
            LineTradeDelivery.headCount(species.getNoOfAnimals()),
            LogisticsPackage.packageCount(species.getNoOfPackages()),
            TradeProductInstance.instancesFrom(species));
    }

    private static TradeLineItem fromComplementTotals(String commodityName, CommodityComplement complement) {
        return new TradeLineItem(
            classificationOf(complement),
            descriptionOf(commodityName),
            null,
            commodityName,
            null,
            null,
            LineTradeDelivery.headCount(complement.getTotalNoOfAnimals()),
            LogisticsPackage.packageCount(complement.getTotalNoOfPackages()),
            null);
    }

    @SuppressWarnings("java:S1168")
    private static List<ApplicableClassification> classificationOf(CommodityComplement complement) {
        ApplicableClassification cn = ApplicableClassification.cn(complement.getTypeOfCommodity());
        return cn != null ? List.of(cn) : null;
    }

    @SuppressWarnings("java:S1168")
    private static List<String> descriptionOf(String commodityName) {
        return commodityName != null ? List.of(commodityName) : null;
    }
}
