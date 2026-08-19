package uk.gov.defra.trade.imports.animals.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final NotificationCopyMapper notificationCopyMapper;
    private final ConsignmentPartyResolver consignmentPartyResolver;
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
        NotificationCopyMapper notificationCopyMapper,
        ConsignmentPartyResolver consignmentPartyResolver,
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
        this.notificationCopyMapper = notificationCopyMapper;
        this.consignmentPartyResolver = consignmentPartyResolver;
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

    /**
     * Replace the notification content at the given reference. Backs {@code PUT /notifications/{ref}}.
     * Requires DRAFT or AMEND — the state-transition entrypoints (submit / amend / cancelAmend /
     * softDelete) handle other cases. Emits a {@code NOTIFICATION_EDITED} outbox event on every save
     * (EUDPA-304), mirroring {@link #updateNotification}.
     */
    @Transactional
    public Notification replace(String referenceNumber, NotificationDto dto,
        String correlationId, Actor actor) {
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (notification.getStatus() != NotificationStatus.DRAFT
            && notification.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot replace notification content with status: " + notification.getStatus());
        }
        if (dto.getConcurrencyToken() == null) {
            throw new BadRequestException("concurrencyToken is required to replace a notification");
        }
        notification.setConcurrencyToken(dto.getConcurrencyToken());
        setNotificationDetails(dto, notification);
        return writeWithOutbox(notification, referenceNumber, correlationId,
            notification.getStatus(), OutboxEventType.NOTIFICATION_EDITED, actor);
    }

    @Transactional
    public Notification copyNotification(String referenceNumber, Long expectedConcurrencyToken) {
        Notification source = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
        if (source.getStatus() != NotificationStatus.DRAFT
            && source.getStatus() != NotificationStatus.SUBMITTED
            && source.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException("Cannot copy notification with status: " + source.getStatus());
        }
        if (expectedConcurrencyToken == null || source.getConcurrencyToken() == null) {
            throw new IllegalStateException(
                "Cannot check copy source concurrencyToken for " + referenceNumber
                    + ": expectedConcurrencyToken=" + expectedConcurrencyToken
                    + ", source.concurrencyToken=" + source.getConcurrencyToken());
        }
        if (!source.getConcurrencyToken().equals(expectedConcurrencyToken)) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                "Copy source " + referenceNumber + " has advanced from expected concurrencyToken "
                    + expectedConcurrencyToken + " to " + source.getConcurrencyToken());
        }
        log.info("Copying notification {}", referenceNumber);
        return createNotification(notificationCopyMapper.toCopyDto(source));
    }

    public NotificationPageResponse findAll(int page, String sort) {
        return findAll(page, sort, null);
    }

    /** Serves {@code GET /notifications/{ref}/fulfilments} — the frontend engine's rehydrate read. */
    public NotificationFulfilmentsView findFulfilmentsView(String referenceNumber) {
        return notificationRepository.findFulfilmentsViewByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
    }

    /** Serves {@code GET /notifications?…} for the dashboard list. */
    public NotificationPageResponse findAll(int page, String sort, String referenceNumber) {
        List<NotificationStatus> dashboardStatuses = List.of(
            NotificationStatus.DRAFT, NotificationStatus.SUBMITTED, NotificationStatus.AMEND);
        var pageable = PageRequest.of(page - 1, listPageSize, NotificationSort.toSort(sort));

        String trimmedReference = StringUtils.trimToNull(referenceNumber);
        if (trimmedReference != null) {
            log.debug("Fetching notification by reference {} for dashboard", trimmedReference);
            Page<NotificationView> matched = notificationRepository
                .findViewByReferenceNumberAndStatusIn(trimmedReference, dashboardStatuses)
                .<Page<NotificationView>>map(notification ->
                    new PageImpl<>(List.of(notification), pageable, 1))
                .orElseGet(() -> Page.empty(pageable));
            log.debug("Found {} notifications for reference {}", matched.getNumberOfElements(),
                trimmedReference);
            return NotificationPageResponse.from(matched);
        }

        log.debug("Fetching notifications page {} (size {}) with sort {}", page, listPageSize, sort);
        Page<NotificationView> result = notificationRepository.findAllViewByStatusIn(
            dashboardStatuses, pageable);
        log.debug("Found {} notifications on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return NotificationPageResponse.from(result);
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
        List<Document> currentFulfilments = notification.getFulfilments();
        notification.setSubmittedFulfilmentsBaseline(
            currentFulfilments == null ? null : deepCopyFulfilments(currentFulfilments));

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
        List<Document> priorFulfilments = notification.getSubmittedFulfilmentsBaseline();
        notification.setFulfilments(
            priorFulfilments == null ? null : deepCopyFulfilments(priorFulfilments));
        notification.setSubmittedFulfilmentsBaseline(null);
        notification.setStatus(NotificationStatus.SUBMITTED);
        notification.setUpdated(LocalDateTime.now());
        // submittedAt is deliberately NOT reset — the amendment is being cancelled to revert to the
        // previously-submitted state, so the original submission timestamp must be preserved.
        // (submittedAt is never touched during the SUBMITTED -> AMEND transition, so it still
        // holds the last-submit value when we get here.)
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
        // Address-book resolution is HTTP, so it happens here rather than inside the lock below:
        // the outbox critical section is bounded by LOCK_AT_MOST_FOR, and a slow address book that
        // outlived it would let a second writer in behind us. It resolves into a copy, so the
        // notification we save keeps the reference alone and only the event carries the details.
        Notification forOutbox = resolvedForOutbox(notification, eventType, actor);

        return executeWithOutboxLock(
            OutboxService.buildAggregateId(referenceNumber), correlationId, eventType.name(), () -> {
                if (targetStatus == NotificationStatus.SUBMITTED
                    && notification.getStatus() == NotificationStatus.AMEND) {
                    notification.setSubmittedBaseline(null);
                    notification.setSubmittedFulfilmentsBaseline(null);
                }
                notification.setStatus(targetStatus);
                notification.setUpdated(LocalDateTime.now());
                if (targetStatus == NotificationStatus.SUBMITTED) {
                    notification.setSubmittedAt(LocalDateTime.now());
                }
                Notification saved = notificationRepository.save(notification);
                forOutbox.setStatus(saved.getStatus());
                forOutbox.setUpdated(saved.getUpdated());
                forOutbox.setSubmittedAt(saved.getSubmittedAt());
                outboxService.appendEvent(forOutbox, eventType, correlationId, actor);
                return saved;
            });
    }

    /**
     * The notification as every outbox event should carry it: references filled in, on a copy, so
     * the stored notification keeps the reference alone.
     *
     * <p>Submit and amend resolve strictly — a GBNAG document cannot carry a nameless party. A
     * draft edit is best-effort, so an address deleted since does not block the save.
     */
    private Notification resolvedForOutbox(
        Notification notification, OutboxEventType eventType, Actor actor) {
        String organisationId = actor != null ? actor.getOrganisationId() : null;
        Notification copy = notification.toBuilder().build();
        return eventType == OutboxEventType.NOTIFICATION_EDITED
            ? consignmentPartyResolver.resolveForDraft(copy, organisationId)
            : consignmentPartyResolver.resolveForSubmission(copy, organisationId);
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
        // Idempotent per REST DELETE convention — a repeat call after a lost response is a no-op.
        if (notification.getStatus() == NotificationStatus.DELETED) {
            return notification;
        }
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
        if (dto.getConcurrencyToken() == null) {
            throw new BadRequestException("concurrencyToken is required to update a notification");
        }
        existing.setConcurrencyToken(dto.getConcurrencyToken());
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
        // Place of origin and the consignment contact are held as copies, so they are
        // stored as they arrive. The other four keep the reference alone.
        notification.setPlaceOfOrigin(ConsignmentParty.inlineOnly(dto.getPlaceOfOrigin()));
        notification.setConsignor(ConsignmentParty.forStorage(dto.getConsignor()));
        notification.setConsignee(ConsignmentParty.forStorage(dto.getConsignee()));
        notification.setImporter(ConsignmentParty.forStorage(dto.getImporter()));
        notification.setDestination(ConsignmentParty.forStorage(dto.getDestination()));
        notification.setCphNumber(dto.getCphNumber());
        notification.setTransport(dto.getTransport());
        notification.setConsignment(ConsignmentParty.inlineOnly(dto.getConsignment()));
        notification.setFulfilments(dto.getFulfilments());
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

    /**
     * BSON round-trip deep clone of a fulfilments list. Callers need independence from the source
     * because amend snapshots the pre-amend fulfilments into {@code submittedFulfilmentsBaseline}
     * and cancel-amend restores from it; a shared reference at any nesting depth would let a
     * later in-memory mutation on one list surface on the other before the notification is persisted.
     * Callers are responsible for the {@code null} case — the helper always returns a fresh list.
     */
    static List<Document> deepCopyFulfilments(List<Document> source) {
        return source.stream()
            .map(d -> Document.parse(d.toJson()))
            .collect(Collectors.toCollection(ArrayList::new));
    }
}
