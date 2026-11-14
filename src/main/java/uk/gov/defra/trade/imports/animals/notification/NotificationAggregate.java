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
 * Aggregate root: identity + metadata at the top, with the well-structured content held as a
 * composed {@link Notification} sub-object (symmetric with the opaque {@link #fulfilments} payload).
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

    /** The moment of the most recent submission; null until the notification is first submitted. */
    private Instant submittedAt;

    /**
     * When this notification becomes eligible for automatic expiry, counted from {@code created};
     * null where expiry does not apply.
     */
    @JsonIgnore
    @Indexed
    private Instant expireAt;

    /** The notification content — parties, commodity, transport, etc. */
    private Notification notification;

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully; never interpreted by the backend. */
    private List<Document> fulfilments;

    /**
     * The {@link #notification} content as it stood when the open amendment started, including
     * inline party details; null when no amendment is open.
     */
    @JsonIgnore
    private Notification preAmendNotification;

    /**
     * The {@link #fulfilments} payload as it stood when the open amendment started; null when no
     * amendment is open.
     */
    @JsonIgnore
    private List<Document> preAmendFulfilments;

    /**
     * True when the {@link #notification} content differs from {@link #preAmendNotification},
     * false when it matches; null when no amendment is open.
     */
    private Boolean answersDifferFromSubmitted;

    /** Returns the notification sub-object, failing fast if absent. */
    public Notification requireNotification() {
        return requireNonNull(notification,
            "NotificationAggregate requires a notification sub-object at this seam");
    }
}
