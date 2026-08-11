package uk.gov.defra.trade.imports.animals.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.OutboxWriteException;
import uk.gov.defra.trade.imports.animals.outbox.Actor;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

@Service
@Slf4j
public class NotificationService {

    private static final String CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER = "Cannot find notification with reference number: ";
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(10);
    private static final int MAX_REF_RETRIES = 3;
    private static final int MAX_LOCK_RETRIES = 2;
  
    private final NotificationRepository notificationRepository;
    private final AuditRepository auditRepository;
    private final DocumentService documentService;
    private final OutboxService outboxService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final NotificationMapper notificationMapper;
    private final NotificationCopyMapper notificationCopyMapper;
    private final PartyResolver partyResolver;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final NotificationTtlConfig ttlConfig;
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
        PartyResolver partyResolver,
        ReferenceNumberGenerator referenceNumberGenerator,
        NotificationTtlConfig ttlConfig,
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
        this.partyResolver = partyResolver;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.ttlConfig = ttlConfig;
        this.lockAtLeastFor = lockAtLeastFor;
        this.listPageSize = listPageSize;
        this.adminPageSize = adminPageSize;
    }

    public Notification saveNotification(NotificationDto notificationDto, String correlationId, Actor actor) {
        if (StringUtils.isBlank(notificationDto.getReferenceNumber())) {
            return createNotification(notificationDto);
        } else {
            return updateNotification(notificationDto, correlationId, actor);
        }
    }

    @Transactional
    public Notification copyNotification(String referenceNumber) {
        Notification source = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (source.getStatus() != NotificationStatus.DRAFT
            && source.getStatus() != NotificationStatus.SUBMITTED
            && source.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException("Cannot copy notification with status: " + source.getStatus());
        }
        log.info("Copying notification {}", referenceNumber);
        return createNotification(notificationCopyMapper.toCopyDto(source));
    }

    /**
     * Reads one notification, with any party held as an address-book reference filled in from the
     * address book.
     *
     * @param organisationId the reader's organisation, forwarded from their authenticated session
     */
    public NotificationResponse findByRef(String referenceNumber, String organisationId) {
        log.debug("Fetching notification for reference {}", referenceNumber);
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        List<AccompanyingDocument> documents = documentService.findByNotificationRef(
            referenceNumber);
        NotificationResponse response = notificationMapper.toResponse(notification).toBuilder()
            .accompanyingDocuments(documents.stream().map(AccompanyingDocumentDto::from).toList())
            .build();
        return partyResolver.resolveAll(response, partyResolver.forOrganisation(organisationId));
    }

    /**
     * Reads a page of notifications for the dashboard, with referenced parties filled in from the
     * address book. One resolver serves the whole page, so an address shared by several rows is
     * fetched once.
     *
     * @param organisationId the reader's organisation, forwarded from their authenticated session
     */
    public NotificationPageResponse findAll(
        int page, String sort, String referenceNumber, String organisationId) {
        List<NotificationStatus> dashboardStatuses = List.of(
            NotificationStatus.DRAFT, NotificationStatus.SUBMITTED, NotificationStatus.AMEND);
        var pageable = PageRequest.of(page - 1, listPageSize, NotificationSort.toSort(sort));
        UnaryOperator<ConsignmentParty> resolver = partyResolver.forOrganisation(organisationId);

        String trimmedReference = StringUtils.trimToNull(referenceNumber);
        if (trimmedReference != null) {
            log.debug("Fetching notification by reference {} for dashboard", trimmedReference);
            Page<Notification> matched = notificationRepository
                .findByReferenceNumberAndStatusIn(trimmedReference, dashboardStatuses)
                .<Page<Notification>>map(notification ->
                    new PageImpl<>(List.of(notification), pageable, 1))
                .orElseGet(() -> Page.empty(pageable));
            log.debug("Found {} notifications for reference {}", matched.getNumberOfElements(),
                trimmedReference);
            return NotificationPageResponse.from(matched, resolver);
        }

        log.debug("Fetching notifications page {} (size {}) with sort {}", page, listPageSize, sort);
        Page<Notification> result = notificationRepository.findAllByStatusIn(
            dashboardStatuses, pageable);
        log.debug("Found {} notifications on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return NotificationPageResponse.from(result, resolver);
    }

    @Transactional
    public Notification submitNotification(String referenceNumber, String correlationId, Actor actor) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

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
            actor);
    }

    @Transactional
    public Notification amendNotification(String referenceNumber, String correlationId, Actor actor) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

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
            actor);
    }

    @Transactional
    public Notification cancelAmendNotification(String referenceNumber) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));

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
        Actor actor) {
        return executeWithOutboxLock(
            OutboxService.buildAggregateId(referenceNumber), correlationId, eventType.name(), () -> {
                if (targetStatus == NotificationStatus.SUBMITTED
                    && notification.getStatus() == NotificationStatus.AMEND) {
                    notification.setSubmittedBaseline(null);
                }
                notification.setStatus(targetStatus);
                notification.setUpdated(LocalDateTime.now());
                Notification saved = notificationRepository.save(notification);
                // Resolve address-book references for GBNAG (in-memory only — already saved).
                String organisationId = actor != null ? actor.getOrganisationId() : null;
                Notification forOutbox = partyResolver.resolveForOutbox(saved, organisationId);
                outboxService.appendEvent(forOutbox, eventType, correlationId, actor);
                return saved;
            });
    }

    private <T> T executeWithOutboxLock(
        String aggregateId,
        String correlationId,
        String operationLabel,
        LockingTaskExecutor.TaskWithResult<T> task) {
        String lockName = "outbox-write:" + aggregateId;
        for (int attempt = 0; attempt <= MAX_LOCK_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(lockAtLeastFor.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new OutboxWriteException(
                        "Interrupted waiting for outbox lock for " + operationLabel,
                        aggregateId, null, correlationId, ie);
                }
            }
            LockConfiguration lockConfig = new LockConfiguration(
                Instant.now(), lockName, LOCK_AT_MOST_FOR, lockAtLeastFor);
            try {
                LockingTaskExecutor.TaskResult<T> result =
                    lockingTaskExecutor.executeWithLock(task, lockConfig);
                if (result.wasExecuted()) {
                    return result.getResult();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new OutboxWriteException(
                    "Outbox write failed during " + operationLabel,
                    aggregateId, null, correlationId, e);
            }
        }
        throw new OutboxWriteException(
            "Could not acquire outbox lock for " + operationLabel,
            aggregateId, null, correlationId);
    }

    @Transactional
    public Notification softDeleteNotification(String referenceNumber) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
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
        deleteNotificationsAndDocuments(referenceNumbers);
        createNotificationAuditRecord(referenceNumbers, auditContext, Result.SUCCESS);
    }

    /**
     * Deletes notifications whose {@code expireAt} has passed, cascading to their accompanying
     * documents, up to {@code batchSize} per call. Called by the non-prod
     * {@code NotificationExpirySweeper}; unlike {@link #deleteByReferenceNumbers} it writes no audit
     * record and tolerates documents vanishing mid-batch (a background sweep should not fail because
     * another actor removed a row concurrently). Notifications with a {@code null} {@code expireAt}
     * — including everything created before this feature shipped — are never selected.
     *
     * @param batchSize maximum number of notifications to remove in this run
     * @return the number of notifications deleted
     */
    @Transactional
    public int deleteExpired(int batchSize) {
        List<NotificationReferenceOnly> due =
            notificationRepository.findExpired(LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return 0;
        }
        List<String> referenceNumbers = due.stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .toList();
        log.info("Expiring {} notification(s)", referenceNumbers.size());
        deleteNotificationsAndDocuments(referenceNumbers);
        return referenceNumbers.size();
    }

    /**
     * Removes the given notifications and their accompanying documents. Shared by the audited,
     * strict-existence {@link #deleteByReferenceNumbers} path and the tolerant {@link #deleteExpired}
     * sweep; carries no audit or existence semantics of its own.
     */
    private void deleteNotificationsAndDocuments(List<String> referenceNumbers) {
        notificationRepository.deleteAllByReferenceNumberIn(referenceNumbers);
        documentService.deleteForNotificationRefs(referenceNumbers);
    }

    /**
     * Stamps {@code expireAt} on a freshly-created notification, but only when both prod safeguards
     * pass: a TTL duration is configured (non-prod config) and the running environment is not prod.
     * Anchored to {@code created}, so a notification expires a fixed window after creation
     * regardless of later activity.
     */
    private void stampExpiry(Notification notification) {
        Integer days = ttlConfig.days();
        if (days == null || ttlConfig.isProd()) {
            return;
        }
        notification.setExpireAt(notification.getCreated().plusDays(days));
    }

    private Notification createNotification(NotificationDto dto) {
        Notification notification = new Notification();
        notification.setCreated(LocalDateTime.now());
        notification.setStatus(NotificationStatus.DRAFT);
        stampExpiry(notification);
        setNotificationDetails(dto, notification);
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            notification.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                Notification saved = notificationRepository.save(notification);
                log.info("Notification saved with reference number: {}", saved.getReferenceNumber());
                return saved;
            } catch (DuplicateKeyException _) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying", attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    private Notification updateNotification(NotificationDto dto, String correlationId, Actor actor) {
        String referenceNumber = dto.getReferenceNumber();
        Notification existing = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (existing.getStatus() != NotificationStatus.DRAFT
            && existing.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot save notification with status: " + existing.getStatus());
        }
        log.info("Updating notification {}", referenceNumber);
        setNotificationDetails(dto, existing);
        return writeWithOutbox(existing, referenceNumber, correlationId, existing.getStatus(),
            OutboxEventType.NOTIFICATION_EDITED, actor);
    }

    private void setNotificationDetails(NotificationDto dto, Notification notification) {
        notification.setOrigin(dto.getOrigin());
        notification.setCommodity(dto.getCommodity());
        notification.setReasonForImport(dto.getReasonForImport());
        notification.setAdditionalDetails(dto.getAdditionalDetails());
        notification.setPlaceOfOrigin(ConsignmentParty.forStorage(dto.getPlaceOfOrigin()));
        notification.setConsignor(ConsignmentParty.forStorage(dto.getConsignor()));
        notification.setConsignee(ConsignmentParty.forStorage(dto.getConsignee()));
        notification.setImporter(ConsignmentParty.forStorage(dto.getImporter()));
        notification.setDestination(ConsignmentParty.forStorage(dto.getDestination()));
        notification.setCphNumber(dto.getCphNumber());
        notification.setTransport(dto.getTransport());
        notification.setConsignment(ConsignmentParty.forStorage(dto.getConsignment()));
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
