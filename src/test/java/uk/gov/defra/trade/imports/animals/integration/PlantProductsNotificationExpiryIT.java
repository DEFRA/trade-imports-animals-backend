package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.DRAFT;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocument;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotification;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationService;

/**
 * Integration test for the plant-products expiry sweep against a real Testcontainers MongoDB.
 * Exercises {@link PlantProductsNotificationService#deleteExpired(int)} directly instead of
 * waiting on the scheduled sweeper, keeping expiry and cascade assertions deterministic.
 */
class PlantProductsNotificationExpiryIT extends IntegrationBase {

    @Autowired
    private PlantProductsNotificationService plantProductsNotificationService;

    @Autowired
    private PlantProductsNotificationRepository plantProductsNotificationRepository;

    @Autowired
    private PlantProductsAccompanyingDocumentRepository plantProductsDocumentRepository;

    @Autowired
    private NotificationRepository animalsNotificationRepository;

    @BeforeEach
    void setUp() {
        plantProductsDocumentRepository.deleteAll();
        plantProductsNotificationRepository.deleteAll();
        animalsNotificationRepository.deleteAll();
    }

    @Test
    void deleteExpired_shouldRemoveDueNotificationAndDocumentsButLeaveNonDueRows() {
        // Given
        String expiredReference = "GBN-PP-26-EXP001";
        String activeReference = "GBN-PP-26-ACT001";
        savePlantNotification(expiredReference, LocalDateTime.now().minusMinutes(1));
        savePlantNotification(activeReference, LocalDateTime.now().plusDays(7));
        savePlantDocument("expired-document", expiredReference);
        savePlantDocument("active-document", activeReference);

        // When
        int deleted = plantProductsNotificationService.deleteExpired(10);

        // Then
        assertThat(deleted).isEqualTo(1);
        assertThat(plantProductsNotificationRepository.findByReferenceNumber(expiredReference))
            .isEmpty();
        assertThat(plantProductsDocumentRepository
            .findByNotificationReferenceNumber(expiredReference)).isEmpty();
        assertThat(plantProductsNotificationRepository.findByReferenceNumber(activeReference))
            .isPresent();
        assertThat(plantProductsDocumentRepository
            .findByNotificationReferenceNumber(activeReference)).hasSize(1);
    }

    @Test
    void deleteExpired_shouldNeverRemoveNotificationWithNullExpireAt() {
        // Given
        String referenceNumber = "GBN-PP-26-LEG001";
        savePlantNotification(referenceNumber, null);

        // When
        int deleted = plantProductsNotificationService.deleteExpired(10);

        // Then
        assertThat(deleted).isZero();
        assertThat(plantProductsNotificationRepository.findByReferenceNumber(referenceNumber))
            .isPresent();
    }

    @Test
    void deleteExpired_shouldRespectBatchBoundAcrossRuns() {
        // Given
        savePlantNotification("GBN-PP-26-BAT001", LocalDateTime.now().minusMinutes(1));
        savePlantNotification("GBN-PP-26-BAT002", LocalDateTime.now().minusMinutes(1));
        savePlantNotification("GBN-PP-26-BAT003", LocalDateTime.now().minusMinutes(1));

        // When / Then
        assertThat(plantProductsNotificationService.deleteExpired(2)).isEqualTo(2);
        assertThat(plantProductsNotificationRepository.findAll()).hasSize(1);
        assertThat(plantProductsNotificationService.deleteExpired(2)).isEqualTo(1);
        assertThat(plantProductsNotificationRepository.findAll()).isEmpty();
    }

    @Test
    void deleteExpired_shouldLeaveDueAnimalsCollectionRowUntouched() {
        // Given
        String plantReference = "GBN-PP-26-ISO001";
        String animalsReference = "GBN-AG-26-ISO001";
        savePlantNotification(plantReference, LocalDateTime.now().minusMinutes(1));
        animalsNotificationRepository.save(Notification.builder()
            .referenceNumber(animalsReference)
            .status(NotificationStatus.DRAFT)
            .created(LocalDateTime.now().minusDays(8))
            .expireAt(LocalDateTime.now().minusMinutes(1))
            .build());

        // When
        int deleted = plantProductsNotificationService.deleteExpired(10);

        // Then
        assertThat(deleted).isEqualTo(1);
        assertThat(plantProductsNotificationRepository.findByReferenceNumber(plantReference))
            .isEmpty();
        assertThat(animalsNotificationRepository.findByReferenceNumber(animalsReference))
            .isPresent();
    }

    private void savePlantNotification(String referenceNumber, LocalDateTime expireAt) {
        plantProductsNotificationRepository.save(PlantProductsNotification.builder()
            .referenceNumber(referenceNumber)
            .status(DRAFT)
            .created(LocalDateTime.now().minusDays(8))
            .expireAt(expireAt)
            .build());
    }

    private void savePlantDocument(String documentReference, String notificationReference) {
        plantProductsDocumentRepository.save(PlantProductsAccompanyingDocument.builder()
            .notificationReferenceNumber(notificationReference)
            .documentType("PHYTOSANITARY_CERTIFICATE")
            .documentReference(documentReference)
            .build());
    }
}
