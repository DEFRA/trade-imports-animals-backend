package uk.gov.defra.trade.imports.plantproducts.configuration;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationService;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plant-products.notification.ttl.sweep", name = "enabled", havingValue = "true")
public class PlantProductsNotificationExpirySweeper {

    static final String LOCK_NAME = "plant-products-notification-expiry-sweeper";

    private final PlantProductsNotificationService notificationService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final PlantProductsNotificationTtlConfig ttlConfig;

    @Scheduled(fixedDelayString = "${plant-products.notification.ttl.sweep.interval-ms:3600000}")
    public void sweep() {
        PlantProductsNotificationTtlConfig.Sweep sweep = ttlConfig.sweep();
        LockConfiguration lockConfig = new LockConfiguration(
            Instant.now(),
            LOCK_NAME,
            sweep.lockAtMostFor(),
            sweep.lockAtLeastFor());

        lockingTaskExecutor.executeWithLock(
            (Runnable) () -> {
                int deleted = notificationService.deleteExpired(sweep.batchSize());
                if (deleted > 0) {
                    log.info("Expiry sweeper deleted {} plant-products notification(s)", deleted);
                }
            },
            lockConfig);
    }
}
