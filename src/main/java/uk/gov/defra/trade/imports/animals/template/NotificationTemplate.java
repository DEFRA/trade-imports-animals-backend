package uk.gov.defra.trade.imports.animals.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;

/**
 * A user's saved set of notification answers. Each template is owned by one user, recorded with
 * the journey set it was made in, and listed newest first. The {@code fulfilments} are opaque and
 * persisted exactly as {@code NotificationAggregate.fulfilments} are, so a template reuses the
 * engine's own payload.
 */
@CompoundIndex(def = "{'ownerId': 1, 'created': -1}")
@org.springframework.data.mongodb.core.mapping.Document(collection = "notification_templates")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
public class NotificationTemplate {

    @EqualsAndHashCode.Include
    @Id
    private String id;

    /** Optimistic locking version managed by Spring Data MongoDB. */
    @Version
    private Long version;

    /** The signed-in user who owns the template: the actor id the frontend sends. */
    private String ownerId;

    /** The owner's organisation when the template was saved. Recorded, not used to scope. */
    private String organisationId;

    /** The journey set the template was made in, such as {@code live-animals}. */
    private String setId;

    /** The template's title, as the user typed it. */
    private String name;

    /**
     * Whether the template is still being created or has been saved. Rows written before
     * templates could be created carry none and read as saved.
     */
    private TemplateStatus status;

    /** The notification answers the template holds, persisted byte-faithfully. */
    @Builder.Default
    private List<Document> fulfilments = new ArrayList<>();

    @CreatedDate
    private Instant created;

    @LastModifiedDate
    private Instant updated;
}
