package uk.gov.defra.trade.imports.animals.notification;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Shared content fields extended by {@link Notification} (content sub-object) and {@link NotificationDto} (wire type). */
@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public abstract class NotificationBase {

    private Origin origin;

    private Commodity commodity;

    private String reasonForImport;

    private AdditionalDetails additionalDetails;

    private ConsignmentParty placeOfOrigin;

    private ConsignmentParty consignor;

    private ConsignmentParty consignee;

    private ConsignmentParty importer;

    private ConsignmentParty destination;

    private ConsignmentParty consignment;

    private String cphNumber;

    private Transport transport;
}
