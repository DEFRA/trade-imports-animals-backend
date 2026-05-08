package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentService;
import uk.gov.defra.trade.imports.animals.audit.Action;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AuditRepository auditRepository;
    private final DocumentService documentService;

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
                "Cannot find notification with reference number: " + referenceNumber));
        List<AccompanyingDocument> documents = documentService.findByNotificationRef(referenceNumber);
        return NotificationResponse.from(notification, documents);
    }

    public List<Notification> findAll() {
        log.debug("Fetching all notifications");
        List<Notification> notifications = notificationRepository.findAll();
        log.debug("Found {} notifications", notifications.size());
        return notifications;
    }

    public List<String> findAllReferenceNumbers() {
        log.debug("Fetching all notification reference numbers");
        return notificationRepository.findAllProjectedBy()
            .stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .toList();
    }

    @Transactional
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

    private String createReferenceNumber(Notification notification) {
        return "DRAFT.IMP." + LocalDate.now().getYear() + "." + notification.getId();
    }

    private Notification createNotification(NotificationDto dto) {
        Notification notification = new Notification();
        notification.setCreated(LocalDateTime.now());
        setNotificationDetails(dto, notification);
        var saved = notificationRepository.save(notification);
        log.info("Notification saved with id: {}", saved.getId());
        saved.setReferenceNumber(createReferenceNumber(saved));
        log.info("Notification reference number set to: {}", saved.getReferenceNumber());
        return notificationRepository.save(saved);
    }

    private Notification updateNotification(NotificationDto dto) {
        Notification existingNotification = notificationRepository.findByReferenceNumber(
            dto.getReferenceNumber()).orElseThrow(() -> new NotFoundException(
            "Cannot find notification with reference number: " + dto.getReferenceNumber()));
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
