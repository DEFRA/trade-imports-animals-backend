package uk.gov.defra.trade.imports.animals.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;

import static java.util.Objects.requireNonNull;

/**
 * Aggregate root: identity + metadata at the top, with the well-structured content held as a
 * composed {@link Notification} sub-object (symmetric with the opaque {@link #fulfilments} payload).
 * Amend baselines snapshot the two sub-objects independently.
 */
@org.springframework.data.mongodb.core.mapping.Document(collection = "notification")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotificationAggregate {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String referenceNumber;

    @Version
    private Long concurrencyToken;

    private NotificationStatus status;

    private LocalDateTime created;

    private LocalDateTime updated;

    /**
     * Timestamp of the most recent submission — set the first time the notification is submitted
     * (from DRAFT) and refreshed whenever it is re-submitted from AMEND. Left unchanged by amend
     * and cancel-amend, so it always points at the latest submission event rather than the
     * original one. Set by {@code submitNotification}; carried into the fulfilment-view projection.
     */
    private LocalDateTime submittedAt;

    /**
     * When this notification becomes eligible for automatic expiry, anchored to {@code created}.
     * Set only for app-created notifications in non-prod environments (see
     * {@code NotificationService.stampExpiry}); {@code null} in prod and for notifications created
     * before this field existed. Backed by a <em>plain</em> index (not a Mongo TTL index) so the
     * expiry sweep can cascade to accompanying documents.
     */
    @JsonIgnore
    @Indexed
    private LocalDateTime expireAt;

    /** The notification content — parties, commodity, transport, etc. Symmetric to {@link #fulfilments}. */
    private Notification notification;

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully; never interpreted by the backend. */
    private List<Document> fulfilments;

    /** Pre-amend snapshot of {@link #notification}. Non-null iff status is AMEND; restored by cancelAmend, cleared by submit-from-amend. */
    @JsonIgnore
    private Notification submittedNotificationBaseline;

    /** Pre-amend snapshot of {@link #fulfilments}. Non-null iff status is AMEND; restored by cancelAmend, cleared by submit-from-amend. */
    @JsonIgnore
    private List<Document> submittedFulfilmentsBaseline;

    /** Returns the notification sub-object, failing fast if absent. Use at seams that require content. */
    public Notification requireNotification() {
        return requireNonNull(notification,
            "NotificationAggregate requires a notification sub-object at this seam");
    }
}
