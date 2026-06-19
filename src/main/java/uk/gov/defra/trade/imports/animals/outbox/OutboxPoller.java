package uk.gov.defra.trade.imports.animals.outbox;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "outbox.poller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    static final String LOCK_NAME = "outbox-poller";

    private final OutboxPublishService outboxPublishService;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final OutboxConfig outboxConfig;

    @Scheduled(fixedDelayString = "${outbox.poller.interval-ms:2000}")
    public void poll() {
        OutboxConfig.Poller poller = outboxConfig.poller();
        LockConfiguration lockConfig = new LockConfiguration(
            Instant.now(),
            LOCK_NAME,
            poller.lockAtMostFor(),
            poller.lockAtLeastFor());

        lockingTaskExecutor.executeWithLock(
            (Runnable) () -> {
                int published = outboxPublishService.publishUnpublishedEvents();
                if (published > 0) {
                    log.debug("Outbox poller published {} event(s)", published);
                }
            },
            lockConfig);
    }
}
