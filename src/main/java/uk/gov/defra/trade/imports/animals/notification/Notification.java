package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notification")
@Data
@SuperBuilder(toBuilder = true)
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
}
