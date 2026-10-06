package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
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
     * A calendar day the user chose, carried as a {@code LocalDate}: {@code YYYY-MM-DD} on the
     * wire and a string in Mongo. It has no time and no zone, so no reader can shift it by a day.
     * Moments such as {@code created} are {@code Instant} instead.
     */
    private LocalDate exitDate;
}
