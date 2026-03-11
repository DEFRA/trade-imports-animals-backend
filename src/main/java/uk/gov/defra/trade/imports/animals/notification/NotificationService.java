package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
    
    private final NotificationRepository notificationRepository;
    
    String saveOriginOfImport(Notification notification) {
        if (notification.getId() == null) {
            var saved = notificationRepository.save(notification);
            log.info("Notification saved with id: {}", saved.getId());
            saved.setReferenceNumber(createReferenceNumber(saved));
            log.info("Notification reference number set to: {}", saved.getReferenceNumber());
            return notificationRepository.save(saved).getReferenceNumber();
        }
        log.info("Notification already exists, updating {}", notification.getReferenceNumber());
        return notificationRepository.save(notification).getReferenceNumber();

    }
    
    private String createReferenceNumber(Notification notification) {
        var paddedId = StringUtils.leftPad(String.valueOf(notification.getId()), 8, "0");
        return "DRAFT.CHEDA." + LocalDate.now().getYear() + "." + paddedId;
    }
}
