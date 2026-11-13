package uk.gov.defra.trade.imports.animals.notification;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

@Slf4j
@Component
public class NotificationExpiryStampingLog {

    private final NotificationTtlConfig ttlConfig;

    public NotificationExpiryStampingLog(NotificationTtlConfig ttlConfig) {
        this.ttlConfig = ttlConfig;
    }

    /**
     * Logs once the application is ready whether new notifications get an expiry stamped in this
     * environment and, when they do not, why.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logExpiryStamping() {
        String environment = ttlConfig.environment();
        Optional<Integer> daysToStamp = ttlConfig.daysToStamp();
        if (daysToStamp.isPresent()) {
            log.info("Notification expiry stamping is on: {} days in environment {}", daysToStamp.get(), environment);
        } else if (ttlConfig.isProd()) {
            log.info("Notification expiry stamping is off: environment {} is prod", environment);
        } else {
            log.info("Notification expiry stamping is off: no days set in environment {}", environment);
        }
    }
}
