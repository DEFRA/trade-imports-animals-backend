package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
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

    static SpecifiedConsignment from(NotificationAggregate notificationAggregate) {
        Notification notification = notificationAggregate.getNotification();
        Transport transport = notification != null ? notification.getTransport() : null;
        AdditionalDetails additionalDetails = notification != null ? notification.getAdditionalDetails() : null;
        // Tri-state: TRUE / FALSE when supplied, null when the field is absent upstream.
        Boolean isOrHasUnweanedAnimals =
            additionalDetails == null || additionalDetails.getUnweanedAnimals() == null
                ? null
                : Boolean.valueOf(additionalDetails.getUnweanedAnimals());
        return new SpecifiedConsignment(
            TradeParty.from(notification != null ? notification.getConsignor() : null),
            TradeParty.from(notification != null ? notification.getConsignee() : null),
            TradeParty.from(notification != null ? notification.getPlaceOfOrigin() : null),
            TradeParty.from(notification != null ? notification.getDestination() : null),
            TradeParty.from(notification != null ? notification.getImporter() : null),
            TradeParty.from(transport != null ? transport.getTransporter() : null),
            TradeCountry.from(notification != null ? notification.getOrigin() : null),
            LogisticsLocation.from(transport != null ? transport.getPortOfEntry() : null),
            LogisticsTransportMovement.from(transport),
            null,
            isOrHasUnweanedAnimals,
            ConsignmentItem.from(notification != null ? notification.getCommodity() : null));
    }
}
