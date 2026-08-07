package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.mongodb.core.index.Indexed;

/**
 * Shared fields for the notification domain. {@link Notification} (entity) and
 * {@link NotificationDto} (API DTO) both extend this class to avoid duplicating the field
 * declarations. {@link Notification} adds the MongoDB {@code @Id} and {@code @Document}
 * annotations; {@link NotificationDto} carries no additional fields.
 */
@Data
@SuperBuilder
@NoArgsConstructor
public abstract class NotificationBase {

    @Indexed(unique = true, sparse = true)
    private String referenceNumber;

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

    private NotificationStatus status;

    private LocalDateTime created;

    private LocalDateTime updated;
}
