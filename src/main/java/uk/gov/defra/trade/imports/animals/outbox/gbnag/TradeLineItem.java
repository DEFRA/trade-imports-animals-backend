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
            descriptionOf(entry.getName()),
            species.getText(),
            entry.getName(),
            typeCodeFor(species, entry),
            null,
            LineTradeDelivery.headCount(species.getNoOfAnimals()),
            LogisticsPackage.packageCount(species.getNoOfPackages()),
            TradeProductInstance.instancesFrom(species));
    }

    private static TradeLineItem fromComplementTotals(CommodityComplement entry) {
        return new TradeLineItem(
            classificationOf(entry),
            descriptionOf(entry.getName()),
            null,
            entry.getName(),
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
        // Legacy Mongo rows often lack commodityCode; type text was the old CN stand-in.
        if (cn == null || cn.isBlank()) {
            cn = entry.getTypeOfCommodity();
        }
        ApplicableClassification classification = ApplicableClassification.cn(cn);
        return classification != null ? List.of(classification) : null;
    }

    @SuppressWarnings("java:S1168")
    private static List<String> descriptionOf(String commodityName) {
        return commodityName != null ? List.of(commodityName) : null;
    }
}
