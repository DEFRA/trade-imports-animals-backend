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
 * Scheduled sweep that deletes app-created notifications whose {@code expireAt} has passed, in
 * non-prod environments only (EUDPA-273). Follows the {@link
 * uk.gov.defra.trade.imports.animals.outbox.OutboxPoller} pattern: {@code @Scheduled} +
 * shedlock so only one instance sweeps at a time.
 *
 * <p>Deliberately <b>without</b> {@code matchIfMissing} — the bean exists only when
 * {@code notification.ttl.sweep.enabled=true} is set explicitly. Prod leaves it {@code false}, so
 * the sweeper never runs there (the sweeping safeguard). Coarse polling and a bounded batch suit
 * 7-day granularity and avoid large delete bursts.
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
