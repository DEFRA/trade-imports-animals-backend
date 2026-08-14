package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;

@org.springframework.data.mongodb.core.mapping.Document(collection = "notification")
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Notification extends NotificationBase {

    @Id
    private String id;

    /** Submitted notification content captured when an amendment begins. */
    @JsonIgnore
    private NotificationContentSnapshot submittedBaseline;

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

    /**
     * Timestamp of the most recent submission — set the first time the notification is submitted
     * (from DRAFT) and refreshed whenever it is re-submitted from AMEND. Left unchanged by amend
     * and cancel-amend, so it always points at the latest submission event rather than the
     * original one. Set by {@code submitNotification}; carried into the fulfilment-view projection.
     */
    private LocalDateTime submittedAt;

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully; never interpreted by the backend. */
    private List<Document> fulfilments;

    /** Pre-amend snapshot of {@link #fulfilments}. Non-null iff status is AMEND; restored by cancelAmend, cleared by submit-from-amend. */
    @JsonIgnore
    private List<Document> submittedFulfilmentsBaseline;
}
