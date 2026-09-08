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

    static TradeLineItem from(String commodityName, CommodityComplement complement) {
        ApplicableClassification cn = ApplicableClassification.cn(complement.getTypeOfCommodity());

        return new TradeLineItem(
            cn != null ? List.of(cn) : null,
            null,
            scientificNameFrom(complement.getSpecies()),
            commodityName,
            null,
            null,
            LineTradeDelivery.headCount(complement.getTotalNoOfAnimals()),
            LogisticsPackage.packageCount(complement.getTotalNoOfPackages()),
            TradeProductInstance.instancesFrom(complement.getSpecies()));
    }

    private static String scientificNameFrom(List<Species> species) {
        return species == null || species.isEmpty() ? null : species.getFirst().getText();
    }
}
