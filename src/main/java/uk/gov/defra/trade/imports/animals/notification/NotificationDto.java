package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.bson.Document;

@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotificationDto extends NotificationBase {

    private String referenceNumber;

    private NotificationStatus status;

    private Instant created;

    private Instant updated;

    private Long concurrencyToken;

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully. */
    private List<Document> fulfilments;
}
