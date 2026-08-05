package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsBadRequestException;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotification;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus;

@Service
@Slf4j
@RequiredArgsConstructor
public class PlantProductsAccompanyingDocumentService {

    private static final String CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER =
        "Cannot find plant-products notification with reference number: ";
    private static final String CANNOT_FIND_DOCUMENT = "Cannot find accompanying document with id: ";

    private final PlantProductsAccompanyingDocumentRepository documentRepository;
    private final PlantProductsNotificationRepository notificationRepository;
    private final PlantProductsAccompanyingDocumentMapper documentMapper;

    public List<PlantProductsAccompanyingDocument> list(String notificationReferenceNumber) {
        requireNotification(notificationReferenceNumber);
        return documentRepository.findByNotificationReferenceNumber(notificationReferenceNumber);
    }

    public PlantProductsAccompanyingDocument create(
        String notificationReferenceNumber, PlantProductsAccompanyingDocumentDto dto) {
        requireWritableNotification(notificationReferenceNumber);
        PlantProductsAccompanyingDocument document = documentMapper.toEntity(dto);
        document.setNotificationReferenceNumber(notificationReferenceNumber);
        document.setCreated(LocalDateTime.now());
        document.setUpdated(LocalDateTime.now());
        PlantProductsAccompanyingDocument saved = documentRepository.save(document);
        log.info("Created accompanying document {} for plant-products notification {}",
            saved.getId(), notificationReferenceNumber);
        return saved;
    }

    public PlantProductsAccompanyingDocument replace(
        String notificationReferenceNumber, String documentId,
        PlantProductsAccompanyingDocumentDto dto) {
        requireWritableNotification(notificationReferenceNumber);
        PlantProductsAccompanyingDocument document = findDocument(
            notificationReferenceNumber, documentId);
        document.setDocumentType(dto.documentType());
        document.setDocumentReference(dto.documentReference());
        document.setIssueDate(dto.issueDate());
        document.setFiles(dto.files());
        document.setUpdated(LocalDateTime.now());
        return documentRepository.save(document);
    }

    public void delete(String notificationReferenceNumber, String documentId) {
        requireWritableNotification(notificationReferenceNumber);
        PlantProductsAccompanyingDocument document = findDocument(
            notificationReferenceNumber, documentId);
        documentRepository.delete(document);
        log.info("Deleted accompanying document {} for plant-products notification {}",
            documentId, notificationReferenceNumber);
    }

    private PlantProductsAccompanyingDocument findDocument(
        String notificationReferenceNumber, String documentId) {
        return documentRepository
            .findByIdAndNotificationReferenceNumber(documentId, notificationReferenceNumber)
            .orElseThrow(() -> new PlantProductsNotFoundException(CANNOT_FIND_DOCUMENT + documentId));
    }

    private PlantProductsNotification requireNotification(String referenceNumber) {
        return notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new PlantProductsNotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
    }

    private void requireWritableNotification(String referenceNumber) {
        PlantProductsNotification notification = requireNotification(referenceNumber);
        if (notification.getStatus() != PlantProductsNotificationStatus.DRAFT
            && notification.getStatus() != PlantProductsNotificationStatus.AMEND) {
            throw new PlantProductsBadRequestException(
                "Cannot modify documents for notification with status: " + notification.getStatus());
        }
    }
}
