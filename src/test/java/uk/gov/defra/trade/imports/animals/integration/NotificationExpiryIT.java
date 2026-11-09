package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static uk.gov.defra.trade.imports.animals.integration.ExpireAtIndexes.assertSingleTtlIndexNamed;
import static uk.gov.defra.trade.imports.animals.integration.ExpireAtIndexes.expireAtIndexes;

import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationExpiryIndex;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

@TestPropertySource(
    properties = {
        "notification.ttl.expiry.enabled=true",
        "spring.data.mongodb.database=notification-expiry-it"
    })
class NotificationExpiryIT extends IntegrationBase {

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
    void deleteAllNotificationsDocumentsAndAudits() {
        notificationRepository.deleteAll();
        accompanyingDocumentRepository.deleteAll();
        auditRepository.deleteAll();
    }

    @BeforeEach
    void putTtlIndexesInPlace() {
        notificationExpiryIndex.ensureTtlIndexes();
    }

    @AfterEach
    void restoreTtlMonitorSleep() {
        setTtlMonitorSleepSecs(60);
    }

    @Test
    void ensureTtlIndexes_shouldReplaceAPlainExpireAtIndexWithTheTtlIndex() {
        replaceExpireAtIndexesWithAPlainIndexOnNotifications();

        notificationExpiryIndex.ensureTtlIndexes();

        assertSingleTtlIndexNamed(
            mongoTemplate, NotificationAggregate.class, "notification_expire_at_ttl");
    }

    @Test
    void ensureTtlIndexes_shouldPutTheTtlIndexInPlace_whenAnotherInstanceDroppedThePlainIndexFirst() {
        replaceExpireAtIndexesWithAPlainIndexOnNotifications();

        new NotificationExpiryIndex(
            templateThatDropsThePlainIndexAfterListingIndexes(),new NotificationTtlConfig(null, "dev"))
            .ensureTtlIndexes();

        assertSingleTtlIndexNamed(
            mongoTemplate, NotificationAggregate.class, "notification_expire_at_ttl");
    }

    @Test
    void expiredNotificationsAndDocuments_areRemovedByMongoWhileUndatedAndUnexpiredOnesAndAuditStay() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(7, ChronoUnit.DAYS);
        String expiredReference = "GBN-AG-26-EXP001";
        String undatedReference = "GBN-AG-26-UND001";
        String unexpiredReference = "GBN-AG-26-UNE001";
        saveNotification(expiredReference, past);
        saveNotification(undatedReference, null);
        saveNotification(unexpiredReference, future);
        saveDocument("expiry-it-doc-1", expiredReference, past);
        saveDocument("expiry-it-doc-2", undatedReference, null);
        saveDocument("expiry-it-doc-3", unexpiredReference, future);
        saveDeletionAudit(expiredReference);

        setTtlMonitorSleepSecs(1);

        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                assertThat(notificationRepository.findByReferenceNumber(expiredReference)).isEmpty();
                assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(expiredReference))
                    .isEmpty();
            });
        assertThat(notificationRepository.findByReferenceNumber(undatedReference)).isPresent();
        assertThat(notificationRepository.findByReferenceNumber(unexpiredReference)).isPresent();
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(undatedReference))
            .hasSize(1);
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(unexpiredReference))
            .hasSize(1);
        assertThat(auditRepository.findAll()).singleElement().satisfies(
            audit -> assertThat(audit.getNotificationReferenceNumbers()).containsExactly(expiredReference));
    }

    private void replaceExpireAtIndexesWithAPlainIndexOnNotifications() {
        IndexOperations notificationIndexes = mongoTemplate.indexOps(NotificationAggregate.class);
        expireAtIndexes(mongoTemplate, NotificationAggregate.class)
            .forEach(index -> notificationIndexes.dropIndex(index.getString("name")));
        notificationIndexes.createIndex(
            new Index().on("expireAt", Sort.Direction.ASC).named("expireAt"));
    }

    private MongoTemplate templateThatDropsThePlainIndexAfterListingIndexes() {
        IndexOperations notificationIndexes = mongoTemplate.indexOps(NotificationAggregate.class);
        IndexOperations droppingIndexes =
            mock(IndexOperations.class, delegatesTo(notificationIndexes));
        doAnswer(invocation -> {
            List<IndexInfo> listed = notificationIndexes.getIndexInfo();
            notificationIndexes.dropIndex("expireAt");
            return listed;
        }).when(droppingIndexes).getIndexInfo();
        MongoTemplate droppingTemplate = spy(mongoTemplate);
        doReturn(droppingIndexes).when(droppingTemplate).indexOps(NotificationAggregate.class);
        return droppingTemplate;
    }

    private void saveNotification(String referenceNumber, Instant expireAt) {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(referenceNumber)
            .status(NotificationStatus.DRAFT)
            .created(Instant.now().minus(8, ChronoUnit.DAYS))
            .expireAt(expireAt)
            .build());
    }

    private void saveDocument(
        String uploadId, String notificationReferenceNumber, Instant expireAt) {
        accompanyingDocumentRepository.save(AccompanyingDocument.builder()
            .uploadId(uploadId)
            .correlationId(uploadId)
            .notificationReferenceNumber(notificationReferenceNumber)
            .scanStatus(ScanStatus.COMPLETE)
            .expireAt(expireAt)
            .build());
    }

    private void saveDeletionAudit(String notificationReferenceNumber) {
        auditRepository.save(Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .notificationReferenceNumbers(List.of(notificationReferenceNumber))
            .numberOfNotifications(1)
            .timestamp(Instant.now().minus(1, ChronoUnit.DAYS))
            .build());
    }

    private void setTtlMonitorSleepSecs(int seconds) {
        mongoClient
            .getDatabase("admin")
            .runCommand(new Document("setParameter", 1).append("ttlMonitorSleepSecs", seconds));
    }
}
