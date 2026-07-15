package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.Transport;

public record SpecifiedConsignment(
    TradeParty consignorParty,
    TradeParty consigneeParty,
    TradeParty despatchParty,
    TradeParty deliveryParty,
    TradeParty importer,
    TradeParty carrier,
    TradeCountry originCountry,
    LogisticsLocation unloadingBaseportLocation,
    List<LogisticsTransportMovement> mainCarriageLogisticsTransportMovement,
    List<TradeCountry> transitTradeCountry,
    Boolean isOrHasUnweanedAnimals,
    List<ConsignmentItem> includedConsignmentItem
) {

    static SpecifiedConsignment from(Notification notification) {
        Transport transport = notification.getTransport();
        return new SpecifiedConsignment(
            TradeParty.from(notification.getConsignor()),
            TradeParty.from(notification.getConsignee()),
            TradeParty.from(notification.getPlaceOfOrigin()),
            TradeParty.from(notification.getDestination()),
            TradeParty.from(notification.getImporter()),
            TradeParty.from(transport != null ? transport.getTransporter() : null),
            TradeCountry.from(notification.getOrigin()),
            LogisticsLocation.from(transport != null ? transport.getPortOfEntry() : null),
            LogisticsTransportMovement.from(transport),
            null,
            unweanedAnimals(notification.getAdditionalDetails()),
            ConsignmentItem.from(notification.getCommodity()));
    }

    private static Boolean unweanedAnimals(AdditionalDetails additionalDetails) {
        if (additionalDetails == null || additionalDetails.getUnweanedAnimals() == null) {
            return null;
        }
        return Boolean.parseBoolean(additionalDetails.getUnweanedAnimals());
    }
}
