package uk.gov.defra.trade.imports.animals.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;

import static java.util.Objects.requireNonNull;

/**
 * A notification's identity and metadata, with its structured content in a {@link Notification}
 * sub-object and its obligation fulfilments as opaque documents.
 */
@org.springframework.data.mongodb.core.mapping.Document(collection = "notification")
@CompoundIndexes({
    @CompoundIndex(
        name = "organisationId_status_created",
        def = "{'organisationId': 1, 'status': 1, 'created': -1}"),
    @CompoundIndex(
        name = "organisationId_status_arrivalDate",
        def = "{'organisationId': 1, 'status': 1, 'notification.transport.arrivalDate': -1}")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotificationAggregate {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String referenceNumber;

    private String organisationId;

    @Version
    private Long concurrencyToken;

    private NotificationStatus status;

    private Instant created;

    private Instant updated;

    private Instant submittedAt;

    @JsonIgnore
    private Instant expireAt;

    private Notification notification;

    private List<Document> fulfilments;

    @JsonIgnore
    private Notification preAmendNotification;

    @JsonIgnore
    private List<Document> preAmendFulfilments;

    /** Returns the notification sub-object, failing fast if absent. */
    public Notification requireNotification() {
        return requireNonNull(notification, "NotificationAggregate has no notification sub-object");
    }
}
