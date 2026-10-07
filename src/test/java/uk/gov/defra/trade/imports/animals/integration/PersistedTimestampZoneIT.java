package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;

/**
 * The five persisted timestamps are stored as the instant they name, and the date-only fields as
 * the {@code YYYY-MM-DD} string they name, whatever the JVM's default zone. Documents whose
 * timestamps were written before EUDPA-565 still read back.
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

    /** Calendar dates: the day the user chose, with no time and no zone. */
    private static final LocalDate ARRIVAL_DATE = LocalDate.parse("2026-07-21");
    private static final LocalDate EXIT_DATE = LocalDate.parse("2026-07-28");

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
     * The two calendar dates, asserted as {@code YYYY-MM-DD} strings in the raw document. A
     * string has no zone, so there is nothing for a BST JVM to shift — were either field still
     * a BSON date, {@code get(..., String.class)} would throw rather than pass.
     *
     * <p>{@code NotificationIT} exercises the same two fields through the API on the way in and
     * out; this covers both through the repository, so neither is left resting on a single layer.
     */
    @Test
    void save_shouldStoreCalendarDatesAsIsoDateStrings_whenJvmDefaultZoneIsBst() {
        // Given — a draft carrying nested arrival and exit dates
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
        assertThat(notification.get("transport", Document.class).get("arrivalDate", String.class))
            .isEqualTo("2026-07-21");
        assertThat(notification.get("exitDate", String.class)).isEqualTo("2026-07-28");
    }

    /**
     * A stored date-only field reads back as the same calendar date whatever zone the reading
     * JVM is in. The document is written under UTC and read under zones either side of it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void findByReferenceNumber_shouldReadBackTheSameCalendarDate_whateverTheJvmDefaultZone(
        String zoneId) {
        // Given — a document written by a UTC JVM
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.DRAFT)
            .created(CREATED)
            .notification(Notification.builder()
                .transport(Transport.builder().arrivalDate(ARRIVAL_DATE).build())
                .exitDate(EXIT_DATE)
                .build())
            .build());

        // When — it is read by a JVM in another zone
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then
        assertThat(read.getNotification().getTransport().getArrivalDate()).isEqualTo(ARRIVAL_DATE);
        assertThat(read.getNotification().getExitDate()).isEqualTo(EXIT_DATE);
    }

    /**
     * The {@code String} to {@code LocalDate} reading converter is registered globally, so this
     * pins that it runs only where the target property is a {@code LocalDate}. A date-shaped
     * string in a {@code String} field, in the untyped {@code fulfilments} payload and in an
     * outbox event's untyped {@code data} map each come back as the string that was stored.
     */
    @Test
    void read_shouldLeaveDateShapedStringsAsStrings_whenTheTargetIsNotALocalDate() {
        // Given — date-shaped strings in a String field and in both untyped payloads
        String dateShaped = "2026-07-21";
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.DRAFT)
            .created(CREATED)
            .notification(Notification.builder().cphNumber(dateShaped).build())
            .fulfilments(List.of(new Document("arrivalDate", dateShaped)))
            .build());
        String eventId = "evt-date-shaped-string";
        mongoTemplate.save(OutboxEvent.builder()
            .eventId(eventId)
            .aggregateId(REF)
            .aggregateVersion(1)
            .timestamp(CREATED)
            // Already published, so the outbox poller leaves it alone.
            .publishedAt(CREATED)
            .data(Map.of("arrivalDate", dateShaped))
            .build());

        try {
            // When
            NotificationAggregate read =
                notificationRepository.findByReferenceNumber(REF).orElseThrow();
            OutboxEvent event = mongoTemplate.findById(eventId, OutboxEvent.class);

            // Then
            assertThat(read.getNotification().getCphNumber()).isEqualTo(dateShaped);
            assertThat(read.getFulfilments().getFirst().get("arrivalDate")).isEqualTo(dateShaped);
            assertThat(event).isNotNull();
            assertThat(event.getData().get("arrivalDate")).isEqualTo(dateShaped);
        } finally {
            mongoTemplate.remove(new Query(Criteria.where("_id").is(eventId)), OutboxEvent.class);
        }
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
     * <p>The nested calendar dates are not seeded. They are stored as strings now, and with no
     * live data there is no migration and no legacy shape to read.
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
            .append("expireAt", Date.from(EXPIRE_AT)));

        // When
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then — every field comes back as the instant the stored BSON date names
        assertThat(read.getCreated()).isEqualTo(CREATED);
        assertThat(read.getUpdated()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getExpireAt()).isEqualTo(EXPIRE_AT);
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
