package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

/**
 * The canonical option-e journey aggregate. Persisted opaquely: the backend never interprets
 * the payload (obligation UUIDs, composite ids, values or unknown fields all round-trip
 * byte-faithfully). See the EUDPA-288 spike for the design.
 *
 * <p>The payload — {@link #fulfilments} and its pre-amend twin {@link #submittedFulfilments} —
 * is a sequence of obligation entries, one per obligation the user has answered. Each entry
 * takes one of two shapes, decided by the obligation's cardinality:
 * <ul>
 *   <li><b>Scalar obligation</b> — {@code { "obligationId": "…", "value": … }} for one-answer
 *       obligations such as country of origin or import purpose.
 *   <li><b>Records-shaped obligation</b> — {@code { "obligationId": "…", "records": [
 *       { "fulfilmentId": "line0", "value": … }, … ] }} for collections such as commodity
 *       lines or accompanying documents; each record has its own identity so nested
 *       collections can be added, edited and removed independently.
 * </ul>
 * Outer list order is the evaluator's map-iteration order and is preserved byte-faithfully —
 * this is load-bearing for the characterisation-oracle tests on the frontend side.
 */
@org.springframework.data.mongodb.core.mapping.Document(collection = "notification_fulfilments")
@CompoundIndexes({
    @CompoundIndex(
        name = "created_at",
        def = "{'createdAt': -1}"),
    @CompoundIndex(
        name = "submitted_at",
        def = "{'submittedAt': -1}"),
    @CompoundIndex(
        name = "copy_idempotency_key",
        def = "{'copyIdempotencyKey': 1}",
        unique = true,
        partialFilter = "{'copyIdempotencyKey': {'$type': 'string'}}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationFulfilments {

    @Id
    private String id;

    /**
     * The live obligation entries — what the user is currently editing (DRAFT/AMEND) or
     * has most recently submitted (SUBMITTED). See the class Javadoc for the entry shape.
     */
    private List<Document> fulfilments;

    /**
     * Pre-amend snapshot of {@link #fulfilments}, kept only while the aggregate is in AMEND
     * so {@code cancelAmend} can restore what was previously submitted.
     *
     * <p>Invariant: non-null iff {@link #status} is {@link NotificationFulfilmentsStatus#AMEND}.
     * {@code amend} copies {@code fulfilments} into this field on entry to AMEND;
     * {@code cancelAmend} copies it back and clears this field; a submit from AMEND
     * accepts the edited content and clears this field.
     */
    private List<Document> submittedFulfilments;

    private NotificationFulfilmentsStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;

    private String copyIdempotencyKey;
}
