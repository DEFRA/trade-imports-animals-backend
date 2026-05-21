package uk.gov.defra.trade.imports.animals.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

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
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final Duration lockAtLeastFor;
    

    public NotificationService(
        NotificationRepository notificationRepository,
        AuditRepository auditRepository,
        DocumentService documentService,
        OutboxService outboxService,
        LockingTaskExecutor lockingTaskExecutor,
        NotificationMapper notificationMapper,
        ReferenceNumberGenerator referenceNumberGenerator
        @Value("${notification.submit.lock-at-least-for}") Duration lockAtLeastFor) {
        this.notificationRepository = notificationRepository;
        this.auditRepository = auditRepository;
        this.documentService = documentService;
        this.outboxService = outboxService;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.notificationMapper = notificationMapper;
        this.lockAtLeastFor = lockAtLeastFor;
        this.referenceNumberGenerator = referenceNumberGenerator;
    }

    public Notification saveOriginOfImport(NotificationDto notificationDto) {
        if (StringUtils.isBlank(notificationDto.getReferenceNumber())) {
            return createNotification(notificationDto);
        } else {
            return updateNotification(notificationDto);
        }
    }

    public NotificationResponse findByRef(String referenceNumber) {
        log.debug("Fetching notification for reference {}", referenceNumber);
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        List<AccompanyingDocument> documents = documentService.findByNotificationRef(
            referenceNumber);
        return notificationMapper.toResponse(notification).toBuilder()
            .accompanyingDocuments(documents.stream().map(AccompanyingDocumentDto::from).toList())
            .build();
    }

    public List<Notification> findAll() {
        log.debug("Fetching all notifications ordered by transport arrival date descending");
        List<Notification> notifications =
            notificationRepository.findAllByOrderByTransport_ArrivalDateDesc();
        log.debug("Found {} notifications", notifications.size());
        return notifications;
    }

    @Transactional
    public Notification submitNotification(String referenceNumber, String correlationId) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

        String aggregateId = OutboxService.buildAggregateId(referenceNumber);
        LockConfiguration lockConfig = new LockConfiguration(
            Instant.now(),
            "outbox-write:" + aggregateId,
            LOCK_AT_MOST_FOR,
            lockAtLeastFor);

        try {
            LockingTaskExecutor.TaskResult<Notification> result = lockingTaskExecutor.executeWithLock(
                (LockingTaskExecutor.TaskWithResult<Notification>) () -> {
                    notification.setStatus(NotificationStatus.SUBMITTED);
                    notification.setUpdated(LocalDateTime.now());
                    Notification saved = notificationRepository.save(notification);
                    outboxService.appendEvent(saved, correlationId);
                    return saved;
                },
                lockConfig);

            if (!result.wasExecuted()) {
                throw new OutboxWriteException(
                    "Could not acquire outbox lock for submission",
                    aggregateId, null, correlationId);
            }
            return result.getResult();
        } catch (OutboxWriteException | DataAccessException e) {
            throw e;
        } catch (Throwable e) {
            throw new OutboxWriteException(
                "Outbox write failed during submission",
                aggregateId, null, correlationId, e);
        }
    }

    public List<String> findAllReferenceNumbers() {
        log.debug("Fetching all notification reference numbers");
        return notificationRepository.findAllProjectedBy()
            .stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .toList();
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

    private Notification createNotification(NotificationDto dto) {
        Notification notification = new Notification();
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

    private Notification updateNotification(NotificationDto dto) {
        Notification existingNotification = notificationRepository.findByReferenceNumber(
            dto.getReferenceNumber()).orElseThrow(() -> new NotFoundException(
            CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + dto.getReferenceNumber()));
        log.info("Notification already exists, updating {}", dto.getReferenceNumber());
        setNotificationDetails(dto, existingNotification);
        return notificationRepository.save(existingNotification);
    }

    private void setNotificationDetails(NotificationDto dto, Notification notification) {
        notification.setOrigin(dto.getOrigin());
        notification.setCommodity(dto.getCommodity());
        notification.setReasonForImport(dto.getReasonForImport());
        notification.setAdditionalDetails(dto.getAdditionalDetails());
        notification.setConsignor(dto.getConsignor());
        notification.setDestination(dto.getDestination());
        notification.setCphNumber(dto.getCphNumber());
        notification.setTransport(dto.getTransport());
        notification.setUpdated(LocalDateTime.now());
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
}
