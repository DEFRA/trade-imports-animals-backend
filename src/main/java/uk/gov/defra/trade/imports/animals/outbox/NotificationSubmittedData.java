package uk.gov.defra.trade.imports.animals.outbox;

import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.Operator;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Transport;

public record NotificationSubmittedData(
    String referenceNumber,
    Origin origin,
    Commodity commodity,
    String reasonForImport,
    AdditionalDetails additionalDetails,
    String cphNumber,
    Transport transport,
    Operator placeOfOrigin,
    Operator consignor,
    Operator consignee,
    Operator importer,
    Operator destination,
    Operator consignment
) {

    static NotificationSubmittedData from(Notification notification) {
        return new NotificationSubmittedData(
            notification.getReferenceNumber(),
            notification.getOrigin(),
            notification.getCommodity(),
            notification.getReasonForImport(),
            notification.getAdditionalDetails(),
            notification.getCphNumber(),
            notification.getTransport(),
            notification.getPlaceOfOrigin(),
            notification.getConsignor(),
            notification.getConsignee(),
            notification.getImporter(),
            notification.getDestination(),
            notification.getConsignment()
        );
    }
}
