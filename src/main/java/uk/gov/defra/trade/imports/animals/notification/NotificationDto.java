package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationDto {

    private String referenceNumber;

    private Origin origin;

    private Commodity commodity;

    private String reasonForImport;

    private AdditionalDetails additionalDetails;

    private Operator placeOfOrigin;

    private Operator consignor;

    private Operator consignee;

    private Operator importer;

    private Operator destination;

    private Operator consignment;

    private String cphNumber;

    private Transport transport;

    private NotificationStatus status;

    private LocalDateTime created;

    private LocalDateTime updated;

}
