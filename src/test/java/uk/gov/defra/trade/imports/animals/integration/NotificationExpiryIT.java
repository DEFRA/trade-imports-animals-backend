package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationExpiryIndex;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

@TestPropertySource(
    properties = {"notification.ttl.days=7", "notification.ttl.expiry.enabled=true"})
class NotificationExpiryIT extends IntegrationBase {

    private static final Document EXPIRE_AT_KEY = new Document("expireAt", 1);

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoClient mongoClient;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private NotificationExpiryIndex notificationExpiryIndex;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        accompanyingDocumentRepository.deleteAll();
        auditRepository.deleteAll();
        notificationExpiryIndex.createIndexes();
    }

    @AfterEach
    void restoreSharedMongo() {
        for (Class<?> entityClass : List.of(NotificationAggregate.class, AccompanyingDocument.class)) {
            expireAtIndexes(entityClass).forEach(
                index -> mongoTemplate.indexOps(entityClass).dropIndex(index.getString("name")));
        }
        setTtlMonitorSleepSecs(60);
    }

    @ParameterizedTest
    @ValueSource(classes = {NotificationAggregate.class, AccompanyingDocument.class})
    void ttlIndex_isOnExpireAtAndExpiresAtThatTime(Class<?> entityClass) {
        // When
        List<Document> indexes = expireAtIndexes(entityClass);

        // Then
        assertThat(indexes).singleElement().satisfies(
            index -> assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero());
    }

    @ParameterizedTest
    @ValueSource(classes = {NotificationAggregate.class, AccompanyingDocument.class})
    void createIndexes_replacesAPlainExpireAtIndexWithTheTtlIndex(Class<?> entityClass) {
        // Given
        expireAtIndexes(entityClass).forEach(
            index -> mongoTemplate.indexOps(entityClass).dropIndex(index.getString("name")));
        mongoTemplate.indexOps(entityClass).createIndex(new Index().on("expireAt", Sort.Direction.ASC));

        // When
        notificationExpiryIndex.createIndexes();

        // Then
        assertThat(expireAtIndexes(entityClass)).singleElement().satisfies(
            index -> assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero());
    }

    @Test
    void expiredNotificationsAndDocuments_areRemovedByMongoWhileUndatedAndUnexpiredOnesAndAuditStay() {
        // Given
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(7, ChronoUnit.DAYS);
        String expiredRef = saveNotification("GBN-AG-26-EXP001", past);
        String undatedRef = saveNotification("GBN-AG-26-UND001", null);
        String activeRef = saveNotification("GBN-AG-26-ACT001", future);
        saveDocument("expiry-it-doc-1", expiredRef, past);
        saveDocument("expiry-it-doc-2", undatedRef, null);
        saveDocument("expiry-it-doc-3", activeRef, future);
        auditRepository.save(Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .notificationReferenceNumbers(List.of(expiredRef))
            .numberOfNotifications(1)
            .timestamp(Instant.now().minus(1, ChronoUnit.DAYS))
            .build());

        // When
        setTtlMonitorSleepSecs(1);

        // Then
        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                assertThat(notificationRepository.findByReferenceNumber(expiredRef)).isEmpty();
                assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(expiredRef))
                    .isEmpty();
            });
        assertThat(notificationRepository.findByReferenceNumber(undatedRef)).isPresent();
        assertThat(notificationRepository.findByReferenceNumber(activeRef)).isPresent();
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(undatedRef))
            .hasSize(1);
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(activeRef))
            .hasSize(1);
        assertThat(auditRepository.findAll()).singleElement().satisfies(
            audit -> assertThat(audit.getNotificationReferenceNumbers()).containsExactly(expiredRef));
    }

    private String saveNotification(String referenceNumber, Instant expireAt) {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(referenceNumber)
            .status(NotificationStatus.DRAFT)
            .created(Instant.now().minus(8, ChronoUnit.DAYS))
            .expireAt(expireAt)
            .build());
        return referenceNumber;
    }

    private void saveDocument(String uploadId, String notificationRef, Instant expireAt) {
        accompanyingDocumentRepository.save(AccompanyingDocument.builder()
            .uploadId(uploadId)
            .correlationId(uploadId)
            .notificationReferenceNumber(notificationRef)
            .scanStatus(ScanStatus.COMPLETE)
            .expireAt(expireAt)
            .build());
    }

    private List<Document> expireAtIndexes(Class<?> entityClass) {
        return mongoTemplate.getCollection(mongoTemplate.getCollectionName(entityClass))
            .listIndexes().into(new ArrayList<>()).stream()
            .filter(index -> EXPIRE_AT_KEY.equals(index.get("key", Document.class)))
            .toList();
    }

    private void setTtlMonitorSleepSecs(int seconds) {
        mongoClient
            .getDatabase("admin")
            .runCommand(new Document("setParameter", 1).append("ttlMonitorSleepSecs", seconds));
    }
}
