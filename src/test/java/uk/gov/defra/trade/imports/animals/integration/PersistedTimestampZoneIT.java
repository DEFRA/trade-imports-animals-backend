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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationSort;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Transport;

/**
 * EUDPA-565 — the five persisted timestamps are stored as the instant they name, whatever the
 * JVM's default zone, and documents written before the change still read back.
 *
 * <p>Every storage assertion here reads the <em>raw BSON</em> rather than round-tripping through
 * the repository. A round trip decodes with the same zone that encoded it, so it cancels any drift
 * out and passes whether the fields are {@code Instant} or {@code LocalDateTime} — it cannot tell
 * the two apart. The two read-side tests deliberately go the other way, through the repository,
 * because what they pin is the decode rather than the encode.
 *
 * <p>The drift these fields were exposed to was never a daylight-saving crossing between write and
 * read. Spring Data's stock JSR-310 pair resolved a {@code LocalDateTime} through {@code
 * ZoneId.systemDefault()} in both directions, but {@code atZone()} takes the offset from the
 * value's own date rather than from "now", so when a value was written or read made no difference
 * and the round trip stayed lossless. What did lose information was a change of <em>zone</em>
 * between the process that wrote and the process that read: a timestamp written by a {@code
 * Europe/London} JVM and read by a UTC one came back an hour early, with nothing about the stored
 * document changed. An {@code Instant} needs no zone to be decoded, so a zone change has nothing to
 * corrupt — which is why the guard below asserts raw BSON.
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
    private static final String NOTIFICATION_COLLECTION = "notification";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        // Captured here rather than in a field initializer: by now the Spring context has loaded
        // Application, which pins the JVM to UTC, so the restore puts that pin back and not the
        // host zone.
        originalTimeZone = TimeZone.getDefault();
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void save_shouldStoreNotificationTimestampsAsTheInstantTheyName_whenJvmDefaultZoneIsBst() {
        // Given — an aggregate carrying all four top-level timestamps
        NotificationAggregate aggregate = NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.SUBMITTED)
            .created(CREATED)
            .updated(UPDATED)
            .submittedAt(SUBMITTED_AT)
            .expireAt(EXPIRE_AT)
            .build();

        // When
        notificationRepository.save(aggregate);

        // Then
        Document stored = storedNotification();
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
     * <p>{@code NotificationIT} exercises the same two fields through the API on the way in and
     * out; this covers both through the repository, so neither is left resting on a single layer.
     */
    @Test
    void save_shouldStoreCalendarDatesAtExactlyUtcMidnight_whenJvmDefaultZoneIsBst() {
        // Given — a draft whose nested arrival and exit dates are UTC-midnight calendar days
        NotificationAggregate aggregate = NotificationAggregate.builder()
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
            .build();

        // When
        notificationRepository.save(aggregate);

        // Then
        Document stored = storedNotification();
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
    void save_shouldStoreTheAuditTimestampAsTheInstantItNames_whenJvmDefaultZoneIsBst() {
        // Given — an audit row stamped mid-BST
        Audit audit = Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .timestamp(AUDIT_TIMESTAMP)
            .numberOfNotifications(1)
            .build();

        // When
        auditRepository.save(audit);

        // Then
        Document stored = mongoTemplate.getCollection("audit").find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("timestamp", Date.class).toInstant()).isEqualTo(AUDIT_TIMESTAMP);
    }

    /**
     * A document written before EUDPA-565 holds a plain BSON date in each of these fields, exactly
     * as one written after it does — the encoding did not change, only the Java type that reads
     * it. Written here as raw BSON rather than through the repository, because the repository can
     * no longer produce the old shape.
     *
     * <p>The nested calendar dates are seeded alongside the four top-level timestamps because
     * those two fields are the ones {@code UtcLocalDateConverters} used to own as {@code
     * LocalDate}. They were written as BSON dates at UTC midnight then and are read as {@code
     * Instant} now, so they are exactly where a legacy read would break if the decode had changed.
     */
    @Test
    void findByReferenceNumber_shouldReadBackEveryTimestamp_whenTheDocumentWasWrittenBeforeTheChange() {
        // Given — a document in the pre-change shape: BSON dates written by the old converters
        mongoTemplate.getCollection(NOTIFICATION_COLLECTION).insertOne(new Document()
            .append("referenceNumber", REF)
            .append("status", NotificationStatus.SUBMITTED.name())
            .append("created", Date.from(CREATED))
            .append("updated", Date.from(SUBMITTED_AT))
            .append("submittedAt", Date.from(SUBMITTED_AT))
            .append("expireAt", Date.from(EXPIRE_AT))
            .append("notification", new Document()
                .append("transport", new Document()
                    .append("portOfEntry", "GBDVR")
                    .append("arrivalDate", Date.from(ARRIVAL_DATE)))
                .append("exitDate", Date.from(EXIT_DATE))));

        // When
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then — every field comes back as the instant the stored BSON date names
        assertThat(read.getCreated()).isEqualTo(CREATED);
        assertThat(read.getUpdated()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getExpireAt()).isEqualTo(EXPIRE_AT);
        assertThat(read.getNotification().getTransport().getArrivalDate())
            .isEqualTo(ARRIVAL_DATE)
            .hasToString("2026-07-21T00:00:00Z");
        assertThat(read.getNotification().getExitDate())
            .isEqualTo(EXIT_DATE)
            .hasToString("2026-07-28T00:00:00Z");
    }

    /**
     * The sort behind {@code ?sort=createdAt} orders on the stored BSON date. Driven through
     * {@link NotificationSort#toSort(String)} and the repository — the same pair the controller
     * uses — rather than a raw driver sort, so it is the application's own ordering of an {@code
     * Instant} field that is pinned here, not MongoDB's.
     */
    @Test
    void findAll_shouldOrderByTheStoredInstant_whenSortedByCreatedAt() {
        // Given — two notifications saved newest-first, so insertion order cannot flatter the sort
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

        // When — the frontend's own sort parameter, resolved the way the controller resolves it
        var ascending = notificationRepository.findAll(
            PageRequest.of(0, 10, NotificationSort.toSort("createdAt,asc")));
        var descending = notificationRepository.findAll(
            PageRequest.of(0, 10, NotificationSort.toSort("createdAt,desc")));

        // Then
        assertThat(ascending.getContent())
            .extracting(NotificationAggregate::getReferenceNumber)
            .containsExactly("GBN-AG-26-TZ0003", "GBN-AG-26-TZ0002");
        assertThat(descending.getContent())
            .extracting(NotificationAggregate::getReferenceNumber)
            .containsExactly("GBN-AG-26-TZ0002", "GBN-AG-26-TZ0003");
    }

    private Document storedNotification() {
        return mongoTemplate.getCollection(NOTIFICATION_COLLECTION)
            .find(new Document("referenceNumber", REF))
            .first();
    }
}
