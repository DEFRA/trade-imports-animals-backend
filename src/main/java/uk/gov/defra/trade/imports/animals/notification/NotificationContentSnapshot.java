package uk.gov.defra.trade.imports.animals.notification;

import lombok.Builder;
import lombok.Value;
import org.mapstruct.factory.Mappers;

/**
 * Point-in-time copy of amendable notification content, captured when a trader
 * starts an amendment so it can be restored if the amendment is cancelled.
 */
@Value
@Builder
public class NotificationContentSnapshot {

    private static final NotificationContentSnapshotMapper MAPPER =
        Mappers.getMapper(NotificationContentSnapshotMapper.class);

    Origin origin;
    Commodity commodity;
    String reasonForImport;
    AdditionalDetails additionalDetails;
    ConsignmentParty placeOfOrigin;
    ConsignmentParty consignor;
    ConsignmentParty consignee;
    ConsignmentParty importer;
    ConsignmentParty destination;
    ConsignmentParty consignment;
    String cphNumber;
    Transport transport;

    static NotificationContentSnapshot from(Notification notification) {
        return MAPPER.capture(notification);
    }

    void applyTo(Notification notification) {
        MAPPER.restore(this, notification);
    }
}
