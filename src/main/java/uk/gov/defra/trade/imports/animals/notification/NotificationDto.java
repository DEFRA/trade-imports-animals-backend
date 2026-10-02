package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.bson.Document;

/**
 * Inbound request shape: the {@code notification} member of {@link SaveNotificationDto}, and the
 * internal carrier {@link NotificationCopyMapper} hands to the create path. It is never a response
 * body — every endpoint returns {@link NotificationAggregate} or one of the view projections — so
 * the aggregate's server-stamped {@code created}/{@code updated} instants are deliberately absent
 * here; a client cannot set them and the server would discard them.
 */
@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotificationDto extends NotificationBase {

    private String referenceNumber;

    private NotificationStatus status;

    private Long concurrencyToken;

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully. */
    private List<Document> fulfilments;
}
