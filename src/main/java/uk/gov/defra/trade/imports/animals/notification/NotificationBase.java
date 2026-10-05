package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
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

    private String purposeInInternalMarket;

    private String destinationCountry;

    private String portOfExit;

    /**
     * A calendar day carried as an instant, so every date on this API has one representation and
     * no reader has to resolve a zone-less value. The producer labels the user's chosen day as
     * UTC midnight rather than converting it from a local zone — converting would shift the day
     * for any reader east or west of that zone, PIMS included.
     */
    private Instant exitDate;
}
