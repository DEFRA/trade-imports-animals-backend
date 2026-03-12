package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public Notification saveOriginOfImport(NotificationDto notificationDto) {
        if (StringUtils.isBlank(notificationDto.getReferenceNumber())) {
            return createNotification(notificationDto);
        } else {
            return updateNotification(notificationDto);
        }
    }

    public List<Notification> findAll() {
        log.debug("Fetching all notifications");
        List<Notification> notifications = notificationRepository.findAll();
        log.debug("Found {} notifications", notifications.size());
        return notifications;
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
        notification.setUpdated(LocalDateTime.now());
    }
}
