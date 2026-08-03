package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.plantproducts.configuration.PlantProductsNotificationTtlConfig;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsBadRequestException;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;

@Service
@Slf4j
public class PlantProductsNotificationService {

    private static final String CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER =
        "Cannot find plant-products notification with reference number: ";
    private static final String CHED_TYPE = "CHEDPP";
    private static final String STUB_ORGANISATION_ID = "stub-org";
    private static final String STUB_ORGANISATION_NAME = "Stubbed organisation";
    private static final int MAX_REF_RETRIES = 3;
    private static final List<PlantProductsNotificationStatus> DASHBOARD_STATUSES = List.of(
        PlantProductsNotificationStatus.DRAFT,
        PlantProductsNotificationStatus.SUBMITTED,
        PlantProductsNotificationStatus.AMEND);

    private final PlantProductsNotificationRepository notificationRepository;
    private final PlantProductsAccompanyingDocumentRepository accompanyingDocumentRepository;
    private final PlantProductsNotificationMapper notificationMapper;
    private final PlantProductsNotificationCopyMapper notificationCopyMapper;
    private final PlantProductsReferenceNumberGenerator referenceNumberGenerator;
    private final PlantProductsNotificationTtlConfig ttlConfig;
    private final int listPageSize;

    public PlantProductsNotificationService(
        PlantProductsNotificationRepository notificationRepository,
        PlantProductsAccompanyingDocumentRepository accompanyingDocumentRepository,
        PlantProductsNotificationMapper notificationMapper,
        PlantProductsNotificationCopyMapper notificationCopyMapper,
        PlantProductsReferenceNumberGenerator referenceNumberGenerator,
        PlantProductsNotificationTtlConfig ttlConfig,
        @Value("${plant-products.notification.list.page-size:25}") int listPageSize) {
        this.notificationRepository = notificationRepository;
        this.accompanyingDocumentRepository = accompanyingDocumentRepository;
        this.notificationMapper = notificationMapper;
        this.notificationCopyMapper = notificationCopyMapper;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.ttlConfig = ttlConfig;
        this.listPageSize = listPageSize;
    }

    public PlantProductsNotification create(PlantProductsNotificationDto dto) {
        if (StringUtils.isNotBlank(dto.getReferenceNumber())) {
            throw new PlantProductsBadRequestException(
                "A new notification must not carry a reference number");
        }
        PlantProductsNotification notification = new PlantProductsNotification();
        stampServerFields(notification);
        stampExpiry(notification);
        notificationMapper.applyContent(dto, notification);
        return saveWithMintedReference(notification);
    }

    public ReplaceResult replace(String referenceNumber, PlantProductsNotificationDto dto) {
        if (!referenceNumber.equals(dto.getReferenceNumber())) {
            throw new PlantProductsBadRequestException(
                "Path reference number and notification body reference number must match");
        }
        PlantProductsNotification notification = notificationRepository
            .findByReferenceNumber(referenceNumber)
            .orElse(null);
        boolean created = notification == null;
        if (created) {
            notification = new PlantProductsNotification();
            notification.setReferenceNumber(referenceNumber);
            stampServerFields(notification);
        } else if (!isWritable(notification)) {
            throw new PlantProductsBadRequestException(
                "Cannot replace notification with status: " + notification.getStatus());
        }
        notificationMapper.applyContent(dto, notification);
        notification.setUpdated(LocalDateTime.now());
        PlantProductsNotification saved = notificationRepository.save(notification);
        log.info("{} plant-products notification {}", created ? "Created" : "Replaced", referenceNumber);
        return new ReplaceResult(saved, created);
    }

    public Optional<PlantProductsNotification> find(String referenceNumber) {
        return notificationRepository.findByReferenceNumber(referenceNumber);
    }

    public PlantProductsNotificationPageResponse findAll(int page, String sort, String referenceNumber) {
        Sort rowSort = PlantProductsNotificationSort.toSort(sort)
            .and(Sort.by(Sort.Direction.ASC, "_id"));
        Pageable pageable = PageRequest.of(
            page - 1, listPageSize, rowSort);

        String trimmedReference = StringUtils.trimToNull(referenceNumber);
        Page<PlantProductsNotification> result;
        if (trimmedReference != null) {
            result = notificationRepository
                .findByReferenceNumberAndStatusIn(trimmedReference, DASHBOARD_STATUSES)
                .<Page<PlantProductsNotification>>map(notification ->
                    new PageImpl<>(List.of(notification), pageable, 1))
                .orElseGet(() -> Page.empty(pageable));
        } else {
            result = notificationRepository.findAllByStatusIn(DASHBOARD_STATUSES, pageable);
        }
        return new PlantProductsNotificationPageResponse(
            result.getContent().stream().map(notificationMapper::toDto).toList(),
            result.getNumber() + 1,
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages());
    }

    @Transactional
    public PlantProductsNotification changeStatus(String referenceNumber, StatusChangeRequest request) {
        PlantProductsNotification notification = notificationRepository
            .findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new PlantProductsNotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        boolean discardChanges = Boolean.TRUE.equals(request.discardChanges());

        return switch (request.status()) {
            case SUBMITTED -> discardChanges
                ? cancelAmendment(notification)
                : submit(notification);
            case AMEND -> startAmendment(notification, discardChanges);
            case DELETED -> softDelete(notification, discardChanges);
            case DRAFT -> throw new PlantProductsBadRequestException(
                "Cannot transition notification to status: DRAFT");
        };
    }

    public PlantProductsNotification copy(String referenceNumber, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PlantProductsBadRequestException("Idempotency-Key must not be blank");
        }

        PlantProductsNotification existingCopy = findCopy(idempotencyKey);
        if (existingCopy != null) {
            log.info("Returning existing plant-products notification copy {} for idempotency key",
                existingCopy.getReferenceNumber());
            return existingCopy;
        }

        PlantProductsNotification source = notificationRepository
            .findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new PlantProductsNotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (source.getStatus() != PlantProductsNotificationStatus.SUBMITTED
            && source.getStatus() != PlantProductsNotificationStatus.AMEND) {
            throw new PlantProductsBadRequestException(
                "Cannot copy notification with status: " + source.getStatus());
        }
        PlantProductsNotification copy = notificationCopyMapper.copyFrom(source);
        copy.setChedType(CHED_TYPE);
        copy.setCopyIdempotencyKey(idempotencyKey);
        stampExpiry(copy);
        log.info("Copying plant-products notification {}", referenceNumber);
        return saveCopyWithMintedReference(copy, idempotencyKey);
    }

    @Transactional
    public int deleteExpired(int batchSize) {
        List<PlantProductsNotificationReferenceOnly> due = notificationRepository
            .findExpired(LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return 0;
        }
        List<String> referenceNumbers = due.stream()
            .map(PlantProductsNotificationReferenceOnly::getReferenceNumber)
            .toList();
        log.info("Expiring {} plant-products notification(s)", referenceNumbers.size());
        notificationRepository.deleteAllByReferenceNumberIn(referenceNumbers);
        referenceNumbers.forEach(accompanyingDocumentRepository::deleteByNotificationReferenceNumber);
        return referenceNumbers.size();
    }

    public boolean isWritable(PlantProductsNotification notification) {
        return notification.getStatus() == PlantProductsNotificationStatus.DRAFT
            || notification.getStatus() == PlantProductsNotificationStatus.AMEND;
    }

    private PlantProductsNotification submit(PlantProductsNotification notification) {
        if (notification.getStatus() != PlantProductsNotificationStatus.DRAFT
            && notification.getStatus() != PlantProductsNotificationStatus.AMEND) {
            throw new PlantProductsBadRequestException(
                "Cannot submit notification with status: " + notification.getStatus());
        }
        notification.setStatus(PlantProductsNotificationStatus.SUBMITTED);
        notification.setSubmittedBaseline(PlantProductsNotificationContentSnapshot.from(notification));
        return saveUpdated(notification);
    }

    private PlantProductsNotification cancelAmendment(PlantProductsNotification notification) {
        if (notification.getStatus() != PlantProductsNotificationStatus.AMEND) {
            throw new PlantProductsBadRequestException(
                "Cannot cancel amendment for notification with status: " + notification.getStatus());
        }
        if (notification.getSubmittedBaseline() == null) {
            throw new PlantProductsBadRequestException(
                "Cannot cancel amendment: no submitted baseline stored for notification");
        }
        notification.getSubmittedBaseline().applyTo(notification);
        notification.setStatus(PlantProductsNotificationStatus.SUBMITTED);
        return saveUpdated(notification);
    }

    private PlantProductsNotification startAmendment(
        PlantProductsNotification notification, boolean discardChanges) {
        if (discardChanges) {
            throw new PlantProductsBadRequestException(
                "discardChanges is only valid when completing an amendment");
        }
        if (notification.getStatus() != PlantProductsNotificationStatus.SUBMITTED) {
            throw new PlantProductsBadRequestException(
                "Cannot amend notification with status: " + notification.getStatus());
        }
        notification.setSubmittedBaseline(PlantProductsNotificationContentSnapshot.from(notification));
        notification.setStatus(PlantProductsNotificationStatus.AMEND);
        return saveUpdated(notification);
    }

    private PlantProductsNotification softDelete(
        PlantProductsNotification notification, boolean discardChanges) {
        if (discardChanges) {
            throw new PlantProductsBadRequestException(
                "discardChanges is only valid when completing an amendment");
        }
        if (notification.getStatus() == PlantProductsNotificationStatus.DELETED) {
            return notification;
        }
        notification.setStatus(PlantProductsNotificationStatus.DELETED);
        return saveUpdated(notification);
    }

    private PlantProductsNotification saveUpdated(PlantProductsNotification notification) {
        notification.setUpdated(LocalDateTime.now());
        return notificationRepository.save(notification);
    }

    private void stampServerFields(PlantProductsNotification notification) {
        notification.setChedType(CHED_TYPE);
        notification.setStatus(PlantProductsNotificationStatus.DRAFT);
        notification.setOwnership(Ownership.builder()
            .assignedOrganisationId(STUB_ORGANISATION_ID)
            .assignedOrganisationName(STUB_ORGANISATION_NAME)
            .build());
        notification.setCreated(LocalDateTime.now());
        notification.setUpdated(LocalDateTime.now());
    }

    private void stampExpiry(PlantProductsNotification notification) {
        Integer days = ttlConfig.days();
        if (days == null || ttlConfig.isProd()) {
            return;
        }
        notification.setExpireAt(notification.getCreated().plusDays(days));
    }

    private PlantProductsNotification saveWithMintedReference(PlantProductsNotification notification) {
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            notification.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                PlantProductsNotification saved = notificationRepository.save(notification);
                log.info("Plant-products notification saved with reference number: {}",
                    saved.getReferenceNumber());
                return saved;
            } catch (DuplicateKeyException e) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying",
                    attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    private PlantProductsNotification saveCopyWithMintedReference(
        PlantProductsNotification copy, String idempotencyKey) {
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            copy.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                PlantProductsNotification saved = notificationRepository.insert(copy);
                saved.setCreated(saved.getCreated().truncatedTo(ChronoUnit.MILLIS));
                saved.setUpdated(saved.getUpdated().truncatedTo(ChronoUnit.MILLIS));
                log.info("Plant-products notification saved with reference number: {}",
                    saved.getReferenceNumber());
                return saved;
            } catch (DuplicateKeyException e) {
                PlantProductsNotification existingCopy = findCopy(idempotencyKey);
                if (existingCopy != null) {
                    log.info("Returning concurrently-created plant-products notification copy {}",
                        existingCopy.getReferenceNumber());
                    return existingCopy;
                }
                log.warn("Reference number collision on copy persistence attempt {}/{}; retrying",
                    attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    private PlantProductsNotification findCopy(String idempotencyKey) {
        return notificationRepository.findByCopyIdempotencyKey(idempotencyKey).orElse(null);
    }

    public record ReplaceResult(PlantProductsNotification notification, boolean created) {

    }
}
