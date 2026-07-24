package uk.gov.defra.trade.imports.animals.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

@Service
@Slf4j
public class NotificationService {

    private static final String CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER = "Cannot find notification with reference number: ";
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(10);
    private static final int MAX_REF_RETRIES = 3;
  
    private final NotificationRepository notificationRepository;
    private final AuditRepository auditRepository;
    private final DocumentService documentService;
    private final OutboxService outboxService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final NotificationMapper notificationMapper;
    private final NotificationCopyMapper notificationCopyMapper;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final Duration lockAtLeastFor;
    private final int listPageSize;
    private final int adminPageSize;

    public NotificationService(
        NotificationRepository notificationRepository,
        AuditRepository auditRepository,
        DocumentService documentService,
        OutboxService outboxService,
        LockingTaskExecutor lockingTaskExecutor,
        NotificationMapper notificationMapper,
        NotificationCopyMapper notificationCopyMapper,
        ReferenceNumberGenerator referenceNumberGenerator,
        @Value("${notification.submit.lock-at-least-for}") Duration lockAtLeastFor,
        @Value("${notification.list.page-size}") int listPageSize,
        @Value("${notification.admin.page-size}") int adminPageSize) {
        this.notificationRepository = notificationRepository;
        this.auditRepository = auditRepository;
        this.documentService = documentService;
        this.outboxService = outboxService;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.notificationMapper = notificationMapper;
        this.notificationCopyMapper = notificationCopyMapper;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.lockAtLeastFor = lockAtLeastFor;
        this.listPageSize = listPageSize;
        this.adminPageSize = adminPageSize;
    }

    public Notification saveOriginOfImport(NotificationDto notificationDto, Owner owner) {
        if (StringUtils.isBlank(notificationDto.getReferenceNumber())) {
            return createNotification(notificationDto, owner);
        } else {
            return updateNotification(notificationDto, owner);
        }
    }

    public ReplaceResult replaceNotification(
        String referenceNumber, NotificationDto notificationDto, Owner owner) {
        if (!referenceNumber.equals(notificationDto.getReferenceNumber())) {
            throw new BadRequestException(
                "Path reference number and notification body reference number must match");
        }

        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElse(null);
        boolean created = notification == null;
        if (created) {
            notification = new Notification();
            notification.setReferenceNumber(referenceNumber);
            notification.setOwner(owner);
            notification.setStatus(NotificationStatus.DRAFT);
            notification.setCreated(LocalDateTime.now());
        } else {
            assertOwner(notification, owner);
            if (hasSameProjection(notification, notificationDto)) {
                return new ReplaceResult(notification, false);
            }
        }

        setNotificationDetails(notificationDto, notification);
        Notification saved = notificationRepository.save(notification);
        log.info("{} notification {}", created ? "Created" : "Replaced", referenceNumber);
        return new ReplaceResult(saved, created);
    }

    @Transactional
    public Notification copyNotification(String referenceNumber, Owner owner) {
        Notification source = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        assertOwner(source, owner);
        if (source.getStatus() != NotificationStatus.DRAFT
            && source.getStatus() != NotificationStatus.SUBMITTED
            && source.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException("Cannot copy notification with status: " + source.getStatus());
        }
        log.info("Copying notification {}", referenceNumber);
        return createNotification(notificationCopyMapper.toCopyDto(source), owner);
    }

    public NotificationResponse findByRef(String referenceNumber, Owner owner) {
        log.debug("Fetching notification for reference {}", referenceNumber);
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        assertOwner(notification, owner);
        List<AccompanyingDocument> documents = documentService.findByNotificationRef(
            referenceNumber);
        return notificationMapper.toResponse(notification).toBuilder()
            .accompanyingDocuments(documents.stream().map(AccompanyingDocumentDto::from).toList())
            .build();
    }

    public NotificationPageResponse findAll(int page, String sort, Owner owner) {
        log.debug("Fetching notifications page {} (size {}) with sort {}", page, listPageSize, sort);
        Page<Notification> result =
            notificationRepository.findAllByOwnerSubAndOwnerOrganisationAndStatusIn(
                owner.sub(),
                owner.organisation(),
                List.of(
                    NotificationStatus.DRAFT,
                    NotificationStatus.SUBMITTED,
                    NotificationStatus.AMEND),
                PageRequest.of(page - 1, listPageSize, NotificationSort.toSort(sort)));
        log.debug("Found {} notifications on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return NotificationPageResponse.from(result);
    }

    @Transactional
    public Notification submitNotification(
        String referenceNumber, String correlationId, Owner owner) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        assertOwner(notification, owner);

        if (notification.getStatus() != NotificationStatus.DRAFT
            && notification.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot submit notification with status: " + notification.getStatus());
        }

        return writeWithOutbox(
            notification,
            referenceNumber,
            correlationId,
            NotificationStatus.SUBMITTED,
            OutboxEventType.NOTIFICATION_SUBMITTED,
            "submission");
    }

    @Transactional
    public Notification amendNotification(
        String referenceNumber, String correlationId, Owner owner) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        assertOwner(notification, owner);

        if (notification.getStatus() != NotificationStatus.SUBMITTED) {
            throw new BadRequestException(
                "Cannot amend notification with status: " + notification.getStatus());
        }

        notification.setSubmittedBaseline(NotificationContentSnapshot.from(notification));

        return writeWithOutbox(
            notification,
            referenceNumber,
            correlationId,
            NotificationStatus.AMEND,
            OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED,
            "amend");
    }

    @Transactional
    public Notification cancelAmendNotification(String referenceNumber, Owner owner) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        assertOwner(notification, owner);

        if (notification.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot cancel amendment for notification with status: " + notification.getStatus());
        }
        if (notification.getSubmittedBaseline() == null) {
            throw new BadRequestException(
                "Cannot cancel amendment: no submitted baseline stored for notification");
        }

        notification.getSubmittedBaseline().applyTo(notification);
        notification.setSubmittedBaseline(null);
        notification.setStatus(NotificationStatus.SUBMITTED);
        notification.setUpdated(LocalDateTime.now());
        log.info("Cancelled amendment for notification {}", referenceNumber);
        return notificationRepository.save(notification);
    }

    private Notification writeWithOutbox(
        Notification notification,
        String referenceNumber,
        String correlationId,
        NotificationStatus targetStatus,
        OutboxEventType eventType,
        String operationLabel) {
        String aggregateId = OutboxService.buildAggregateId(referenceNumber);
        LockConfiguration lockConfig = new LockConfiguration(
            Instant.now(),
            "outbox-write:" + aggregateId,
            LOCK_AT_MOST_FOR,
            lockAtLeastFor);

        try {
            LockingTaskExecutor.TaskResult<Notification> result = lockingTaskExecutor.executeWithLock(
                (LockingTaskExecutor.TaskWithResult<Notification>) () -> {
                    if (targetStatus == NotificationStatus.SUBMITTED
                        && notification.getStatus() == NotificationStatus.AMEND) {
                        notification.setSubmittedBaseline(null);
                    }
                    notification.setStatus(targetStatus);
                    notification.setUpdated(LocalDateTime.now());
                    Notification saved = notificationRepository.save(notification);
                    outboxService.appendEvent(saved, eventType, correlationId);
                    return saved;
                },
                lockConfig);

            if (!result.wasExecuted()) {
                throw new OutboxWriteException(
                    "Could not acquire outbox lock for " + operationLabel,
                    aggregateId, null, correlationId);
            }
            return result.getResult();
        } catch (OutboxWriteException | DataAccessException e) {
            throw e;
        } catch (Throwable e) {
            throw new OutboxWriteException(
                "Outbox write failed during " + operationLabel,
                aggregateId, null, correlationId, e);
        }
    }

    @Transactional
    public Notification softDeleteNotification(String referenceNumber, Owner owner) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        assertOwner(notification, owner);
        if (notification.getStatus() != NotificationStatus.DRAFT
            && notification.getStatus() != NotificationStatus.SUBMITTED
            && notification.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot delete notification with status: " + notification.getStatus());
        }
        notification.setStatus(NotificationStatus.DELETED);
        notification.setUpdated(LocalDateTime.now());
        return notificationRepository.save(notification);
    }

    public ReferenceNumberPageResponse findAllReferenceNumbers(int page) {
        log.debug("Fetching notification reference numbers page {} (size {})", page, listPageSize);
        Page<NotificationReferenceOnly> result = notificationRepository.findAllProjectedBy(
            PageRequest.of(page, adminPageSize, Sort.by(Direction.DESC, "created")));
        log.debug("Found {} reference numbers on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return ReferenceNumberPageResponse.from(result);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public void deleteByReferenceNumbers(List<String> referenceNumbers, AuditContext auditContext) {
        if (referenceNumbers == null || referenceNumbers.isEmpty()) {
            return;
        }
        List<NotificationReferenceOnly> found = notificationRepository.findAllByReferenceNumberIn(
            referenceNumbers);
        Set<String> foundRefs = found.stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .collect(Collectors.toSet());
        List<String> missing = referenceNumbers.stream()
            .filter(ref -> !foundRefs.contains(ref))
            .toList();
        if (!missing.isEmpty()) {
            createNotificationAuditRecord(referenceNumbers, auditContext, Result.FAILURE);
            throw new NotFoundException(
                "Cannot find notifications with reference numbers: " + String.join(", ", missing));
        }
        log.info("Deleting {} notifications", found.size());
        notificationRepository.deleteAllByReferenceNumberIn(referenceNumbers);
        documentService.deleteForNotificationRefs(referenceNumbers);
        createNotificationAuditRecord(referenceNumbers, auditContext, Result.SUCCESS);
    }

    private Notification createNotification(NotificationDto dto, Owner owner) {
        Notification notification = new Notification();
        notification.setOwner(owner);
        notification.setCreated(LocalDateTime.now());
        notification.setStatus(NotificationStatus.DRAFT);
        setNotificationDetails(dto, notification);
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            notification.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                Notification saved = notificationRepository.save(notification);
                log.info("Notification saved with reference number: {}", saved.getReferenceNumber());
                return saved;
            } catch (DuplicateKeyException e) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying", attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    private Notification updateNotification(NotificationDto dto, Owner owner) {
        Notification existingNotification = notificationRepository.findByReferenceNumber(
            dto.getReferenceNumber()).orElseThrow(() -> new NotFoundException(
            CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + dto.getReferenceNumber()));
        assertOwner(existingNotification, owner);
        log.info("Notification already exists, updating {}", dto.getReferenceNumber());
        setNotificationDetails(dto, existingNotification);
        return notificationRepository.save(existingNotification);
    }

    private void setNotificationDetails(NotificationDto dto, Notification notification) {
        notification.setOrigin(dto.getOrigin());
        notification.setCommodity(dto.getCommodity());
        notification.setReasonForImport(dto.getReasonForImport());
        notification.setAdditionalDetails(dto.getAdditionalDetails());
        notification.setPlaceOfOrigin(dto.getPlaceOfOrigin());
        notification.setConsignor(dto.getConsignor());
        notification.setConsignee(dto.getConsignee());
        notification.setImporter(dto.getImporter());
        notification.setDestination(dto.getDestination());
        notification.setCphNumber(dto.getCphNumber());
        notification.setTransport(dto.getTransport());
        notification.setConsignment(dto.getConsignment());
        notification.setUpdated(LocalDateTime.now());
    }

    private boolean hasSameProjection(Notification notification, NotificationDto dto) {
        return Objects.equals(notification.getOrigin(), dto.getOrigin())
            && Objects.equals(notification.getCommodity(), dto.getCommodity())
            && Objects.equals(notification.getReasonForImport(), dto.getReasonForImport())
            && Objects.equals(notification.getAdditionalDetails(), dto.getAdditionalDetails())
            && Objects.equals(notification.getPlaceOfOrigin(), dto.getPlaceOfOrigin())
            && Objects.equals(notification.getConsignor(), dto.getConsignor())
            && Objects.equals(notification.getConsignee(), dto.getConsignee())
            && Objects.equals(notification.getImporter(), dto.getImporter())
            && Objects.equals(notification.getDestination(), dto.getDestination())
            && Objects.equals(notification.getCphNumber(), dto.getCphNumber())
            && Objects.equals(notification.getTransport(), dto.getTransport())
            && Objects.equals(notification.getConsignment(), dto.getConsignment());
    }

    private void assertOwner(Notification notification, Owner owner) {
        if (!owner.equals(notification.getOwner())) {
            throw new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER
                    + notification.getReferenceNumber());
        }
    }

    private void createNotificationAuditRecord(
        List<String> referenceNumbers, AuditContext auditContext, Result result) {
        Audit auditRecord = Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(result)
            .notificationReferenceNumbers(referenceNumbers)
            .numberOfNotifications(referenceNumbers.size())
            .traceId(auditContext.traceId())
            .userId(auditContext.userId())
            .timestamp(LocalDateTime.now())
            .build();

        auditRepository.save(auditRecord);
    }

    public record ReplaceResult(Notification notification, boolean created) {

    }
}
