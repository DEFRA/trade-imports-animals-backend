package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Shared content fields extended by {@link Notification} (content sub-object) and {@link NotificationDto} (wire type). */
@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public abstract class NotificationBase {

    private Origin origin;

    @Builder.Default
    private List<Commodity> commodities = new ArrayList<>();

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

    /** The exit date the user chose, as YYYY-MM-DD on the wire. */
    private LocalDate exitDate;
}
