package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Transport;

/**
 * EUDPA-565 — the five persisted timestamps are stored as the instant they name, whatever the
 * JVM's default zone, and documents written before the change still read back.
 *
 * <p>Every assertion here reads the <em>raw BSON</em> rather than round-tripping through the
 * repository. A round trip decodes with the same zone that encoded it, so it cancels any drift
 * out and passes whether the fields are {@code Instant} or {@code LocalDateTime} — it cannot tell
 * the two apart. {@code PersistedTimestampZoneSpikeTest} works through why in detail.
 *
 * <p>The zone is pinned to {@code Europe/London} rather than left to the host: on a UTC CI
 * container the drift these tests guard against is zero, so the test would pass without proving
 * anything. {@code TimeZone.setDefault} is JVM-wide, hence the restore in {@link #restoreTimeZone()}.
 */
class PersistedTimestampZoneIT extends IntegrationBase {

    /** Mid-BST, when Europe/London is UTC+01:00 — the offset that would show up as drift. */
    private static final Instant CREATED = Instant.parse("2026-07-21T00:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-07-22T23:45:10.123456Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-22T23:45:10Z");
    private static final Instant EXPIRE_AT = Instant.parse("2026-08-21T00:30:00Z");
    private static final Instant AUDIT_TIMESTAMP = Instant.parse("2026-07-23T00:15:00Z");

    /** Calendar dates: the day the user chose, labelled UTC midnight by the frontend. */
    private static final Instant ARRIVAL_DATE = Instant.parse("2026-07-21T00:00:00Z");
    private static final Instant EXIT_DATE = Instant.parse("2026-07-28T00:00:00Z");

    private static final String REF = "GBN-AG-26-TZ0001";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void notificationTimestamps_areStoredAsTheInstantTheyName_whenJvmDefaultZoneIsBst() {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.SUBMITTED)
            .created(CREATED)
            .updated(UPDATED)
            .submittedAt(SUBMITTED_AT)
            .expireAt(EXPIRE_AT)
            .build());

        Document stored = mongoTemplate.getCollection("notification")
            .find(new Document("referenceNumber", REF))
            .first();

        assertThat(stored).isNotNull();
        assertThat(stored.get("created", Date.class).toInstant()).isEqualTo(CREATED);
        assertThat(stored.get("submittedAt", Date.class).toInstant()).isEqualTo(SUBMITTED_AT);
        assertThat(stored.get("expireAt", Date.class).toInstant()).isEqualTo(EXPIRE_AT);
        // BSON dates are millisecond-precision, so the microseconds on UPDATED are truncated on
        // the way in. That is storage, not zone drift — the millisecond value is exact.
        assertThat(stored.get("updated", Date.class).toInstant())
            .isEqualTo(Instant.parse("2026-07-22T23:45:10.123Z"));
    }

    /**
     * The two calendar dates, asserted at exactly UTC midnight in the raw BSON. This is the half
     * of the zone guarantee that keeps {@code TransportEvent.scheduledOccurrenceDateTime}
     * byte-identical — the emitted string is the stored instant, so if either date drifted an
     * hour the GB-NAG event would name the previous day to every UTC reader, PIMS included.
     *
     * <p>{@code NotificationIT.post_shouldPersistArrivalDateAsUtcStartOfDay_whenJvmDefaultZoneIsBst}
     * covers {@code arrivalDate} through the API; this covers both through the repository, so
     * neither field is left resting on a single test in one layer.
     */
    @Test
    void calendarDates_areStoredAtExactlyUtcMidnight_whenJvmDefaultZoneIsBst() {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.DRAFT)
            .created(CREATED)
            .notification(Notification.builder()
                .transport(Transport.builder()
                    .portOfEntry("GBDVR")
                    .arrivalDate(ARRIVAL_DATE)
                    .build())
                .exitDate(EXIT_DATE)
                .build())
            .build());

        Document stored = mongoTemplate.getCollection("notification")
            .find(new Document("referenceNumber", REF))
            .first();

        assertThat(stored).isNotNull();
        Document notification = stored.get("notification", Document.class);
        assertThat(notification.get("transport", Document.class).get("arrivalDate", Date.class).toInstant())
            .isEqualTo(ARRIVAL_DATE)
            .hasToString("2026-07-21T00:00:00Z");
        assertThat(notification.get("exitDate", Date.class).toInstant())
            .isEqualTo(EXIT_DATE)
            .hasToString("2026-07-28T00:00:00Z");
    }

    @Test
    void auditTimestamp_isStoredAsTheInstantItNames_whenJvmDefaultZoneIsBst() {
        auditRepository.save(Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .timestamp(AUDIT_TIMESTAMP)
            .numberOfNotifications(1)
            .build());

        Document stored = mongoTemplate.getCollection("audit").find().first();

        assertThat(stored).isNotNull();
        assertThat(stored.get("timestamp", Date.class).toInstant()).isEqualTo(AUDIT_TIMESTAMP);
    }

    /**
     * A document written before EUDPA-565 holds a plain BSON date in each of these fields, exactly
     * as one written after it does — the encoding did not change, only the Java type that reads
     * it. Written here as raw BSON rather than through the repository, because the repository can
     * no longer produce the old shape.
     */
    @Test
    void aDocumentWrittenBeforeTheChange_stillReadsBack() {
        mongoTemplate.getCollection("notification").insertOne(new Document()
            .append("referenceNumber", REF)
            .append("status", NotificationStatus.SUBMITTED.name())
            .append("created", Date.from(CREATED))
            .append("updated", Date.from(SUBMITTED_AT))
            .append("submittedAt", Date.from(SUBMITTED_AT))
            .append("expireAt", Date.from(EXPIRE_AT)));

        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        assertThat(read.getCreated()).isEqualTo(CREATED);
        assertThat(read.getUpdated()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    /**
     * The sort behind {@code ?sort=createdAt} orders on the stored BSON date. Both the old and the
     * new Java type encode to that same type, so the order is unchanged by this ticket — pinned
     * here because the acceptance criteria call for it.
     */
    @Test
    void createdSortOrder_followsTheStoredInstant() {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-TZ0002")
            .status(NotificationStatus.DRAFT)
            .created(Instant.parse("2026-01-02T10:00:00Z"))
            .build());
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-TZ0003")
            .status(NotificationStatus.DRAFT)
            .created(Instant.parse("2026-01-01T10:00:00Z"))
            .build());

        assertThat(mongoTemplate.getCollection("notification")
            .find()
            .sort(new Document("created", 1))
            .map(d -> d.getString("referenceNumber")))
            .containsExactly("GBN-AG-26-TZ0003", "GBN-AG-26-TZ0002");
    }
}
