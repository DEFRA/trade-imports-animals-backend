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

class PersistedTimestampZoneIT extends IntegrationBase {

    private static final Instant CREATED = Instant.parse("2026-07-21T00:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-07-22T23:45:10.123456Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-22T23:45:10Z");
    private static final Instant EXPIRE_AT = Instant.parse("2026-08-21T00:30:00Z");
    private static final Instant AUDIT_TIMESTAMP = Instant.parse("2026-07-23T00:15:00Z");

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
        // Given
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
        assertThat(stored.get("updated", Date.class).toInstant())
            .isEqualTo(Instant.parse("2026-07-22T23:45:10.123Z"));
    }

    @Test
    void save_shouldStoreCalendarDatesAsIsoDateStrings_whenJvmDefaultZoneIsBst() {
        // Given
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

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void findByReferenceNumber_shouldReadBackTheSameCalendarDate_whateverTheJvmDefaultZone(
        String zoneId) {
        // Given
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

        // When
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then
        assertThat(read.getNotification().getTransport().getArrivalDate()).isEqualTo(ARRIVAL_DATE);
        assertThat(read.getNotification().getExitDate()).isEqualTo(EXIT_DATE);
    }

    @Test
    void read_shouldLeaveDateShapedStringsAsStrings_whenTheTargetIsNotALocalDate() {
        // Given
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
        // Given
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

    @Test
    void findByReferenceNumber_shouldReadBackEveryTimestamp_whenInsertedAsRawBsonDates() {
        // Given
        mongoTemplate.getCollection(NOTIFICATION_COLLECTION).insertOne(new Document()
            .append("referenceNumber", REF)
            .append("status", NotificationStatus.SUBMITTED.name())
            .append("created", Date.from(CREATED))
            .append("updated", Date.from(SUBMITTED_AT))
            .append("submittedAt", Date.from(SUBMITTED_AT))
            .append("expireAt", Date.from(EXPIRE_AT)));

        // When
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then
        assertThat(read.getCreated()).isEqualTo(CREATED);
        assertThat(read.getUpdated()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    @Test
    void findAll_shouldOrderByTheStoredInstant_whenSortedByCreatedAt() {
        // Given
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

        // When
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
