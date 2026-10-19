package uk.gov.defra.trade.imports.animals.notification;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

/**
 * Deletes notifications whose {@code expireAt} has passed, one batch per scheduled run, holding a
 * shedlock so one instance sweeps at a time. Exists only when
 * {@code notification.ttl.sweep.enabled=true}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.ttl.sweep", name = "enabled", havingValue = "true")
public class NotificationExpirySweeper {

    static final String LOCK_NAME = "notification-expiry-sweeper";

    private final NotificationService notificationService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final NotificationTtlConfig ttlConfig;

    @Scheduled(fixedDelayString = "${notification.ttl.sweep.interval-ms:3600000}")
    public void sweep() {
        NotificationTtlConfig.Sweep sweep = ttlConfig.sweep();
        LockConfiguration lockConfig = new LockConfiguration(
            Instant.now(),
            LOCK_NAME,
            sweep.lockAtMostFor(),
            sweep.lockAtLeastFor());

        lockingTaskExecutor.executeWithLock(
            (Runnable) () -> {
                int deleted = notificationService.deleteExpired(sweep.batchSize());
                if (deleted > 0) {
                    log.info("Expiry sweeper deleted {} notification(s)", deleted);
                }
            },
            lockConfig);
    }
}
