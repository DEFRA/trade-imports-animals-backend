package uk.gov.defra.trade.imports.animals.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes legacy notifications with no owning organisation before scoped enforcement.
 */
@Component
@Slf4j
public class OrganisationIdCleanup {

    private final NotificationRepository notificationRepository;

    public OrganisationIdCleanup(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void deleteNotificationsWithoutOrganisationId() {
        long removed = notificationRepository.deleteByOrganisationIdIsNull();
        if (removed > 0) {
            log.info("Removed {} notification(s) with no organisationId", removed);
        }
    }
}
