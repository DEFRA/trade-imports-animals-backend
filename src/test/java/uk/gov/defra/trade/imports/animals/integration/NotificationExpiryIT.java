package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationService;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

/**
 * Integration test for the notification expiry sweep against a real Testcontainers MongoDB.
 * Exercises {@link NotificationService#deleteExpired(int)} directly (rather than waiting on the
 * scheduled {@code NotificationExpirySweeper}) so the assertions are deterministic. Verifies the
 * expiry cascade removes notifications and their accompanying documents, leaves audit records and
 * non-due notifications untouched, and respects the batch bound.
 */
class NotificationExpiryIT extends IntegrationBase {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @Autowired
    private AuditRepository auditRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        accompanyingDocumentRepository.deleteAll();
        auditRepository.deleteAll();
    }

    private void saveNotification(String referenceNumber, LocalDateTime expireAt) {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(referenceNumber)
            .status(NotificationStatus.DRAFT)
            .created(LocalDateTime.now().minusDays(8))
            .expireAt(expireAt)
            .build());
    }

    private void saveDocument(String uploadId, String notificationRef) {
        accompanyingDocumentRepository.save(AccompanyingDocument.builder()
            .uploadId(uploadId)
            .correlationId(uploadId)
            .notificationReferenceNumber(notificationRef)
            .scanStatus(ScanStatus.COMPLETE)
            .build());
    }

    @Test
    void deleteExpired_removesDueNotificationAndDocuments_leavingAuditAndNonDueUntouched() {
        String expiredRef = "GBN-AG-26-EXP001";
        String activeRef = "GBN-AG-26-ACT001";
        saveNotification(expiredRef, LocalDateTime.now().minusMinutes(1));
        saveNotification(activeRef, LocalDateTime.now().plusDays(7));
        saveDocument("expiry-it-doc-1", expiredRef);
        saveDocument("expiry-it-doc-2", expiredRef);
        saveDocument("expiry-it-doc-3", activeRef);
        auditRepository.save(Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .notificationReferenceNumbers(List.of(expiredRef))
            .numberOfNotifications(1)
            .timestamp(LocalDateTime.now().minusDays(1))
            .build());

        int deleted = notificationService.deleteExpired(10);

        assertThat(deleted).isEqualTo(1);
        // Due notification and its documents are gone.
        assertThat(notificationRepository.findByReferenceNumber(expiredRef)).isEmpty();
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(expiredRef))
            .isEmpty();
        // Non-due notification and its document survive.
        assertThat(notificationRepository.findByReferenceNumber(activeRef)).isPresent();
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(activeRef))
            .hasSize(1);
        // AC: audit records referencing the expired notification are left untouched.
        assertThat(auditRepository.findAll())
            .singleElement()
            .satisfies(audit -> assertThat(audit.getNotificationReferenceNumbers())
                .containsExactly(expiredRef));
    }

    @Test
    void deleteExpired_neverRemovesNotificationsWithNullExpireAt() {
        // Pre-change notifications carry no expireAt and must never be swept (AC: existing
        // notifications are not deleted on deploy).
        String legacyRef = "GBN-AG-26-LEG001";
        saveNotification(legacyRef, null);

        int deleted = notificationService.deleteExpired(10);

        assertThat(deleted).isZero();
        assertThat(notificationRepository.findByReferenceNumber(legacyRef)).isPresent();
    }

    @Test
    void deleteExpired_removesAtMostBatchSizePerRun() {
        saveNotification("GBN-AG-26-BAT001", LocalDateTime.now().minusMinutes(1));
        saveNotification("GBN-AG-26-BAT002", LocalDateTime.now().minusMinutes(1));
        saveNotification("GBN-AG-26-BAT003", LocalDateTime.now().minusMinutes(1));

        assertThat(notificationService.deleteExpired(2)).isEqualTo(2);
        assertThat(notificationRepository.findAll()).hasSize(1);

        assertThat(notificationService.deleteExpired(2)).isEqualTo(1);
        assertThat(notificationRepository.findAll()).isEmpty();
    }
}
