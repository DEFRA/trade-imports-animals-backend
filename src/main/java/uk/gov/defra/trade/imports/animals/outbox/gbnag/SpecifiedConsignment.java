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
    LogisticsLocation finalDestinationLocation,
    List<LogisticsTransportMovement> mainCarriageLogisticsTransportMovement,
    List<TradeCountry> transitTradeCountry,
    Boolean isOrHasUnweanedAnimals,
    List<ConsignmentItem> includedConsignmentItem
) {

    static SpecifiedConsignment from(Notification notification) {
        Transport transport = notification.getTransport();
        AdditionalDetails additionalDetails = notification.getAdditionalDetails();
        return new SpecifiedConsignment(
            TradeParty.from(notification.getConsignor()),
            TradeParty.from(notification.getConsignee()),
            TradeParty.from(notification.getPlaceOfOrigin()),
            TradeParty.from(notification.getDestination()),
            TradeParty.from(notification.getImporter()),
            TradeParty.from(transport != null ? transport.getTransporter() : null),
            TradeCountry.from(notification.getOrigin()),
            LogisticsLocation.from(transport != null ? transport.getPortOfEntry() : null),
            LogisticsLocation.cph(notification.getCphNumber()),
            LogisticsTransportMovement.from(transport),
            TradeCountry.fromCountryCodes(transport != null ? transport.getTransitedCountries() : null),
            unweanedAnimalsAnswer(additionalDetails),
            ConsignmentItem.from(notification));
    }

    private static Boolean unweanedAnimalsAnswer(AdditionalDetails additionalDetails) {
        if (additionalDetails == null || additionalDetails.getUnweanedAnimals() == null) {
            return null;
        }
        return Boolean.valueOf(additionalDetails.getUnweanedAnimals());
    }
}
