package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.Optional;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

@ExtendWith(MockitoExtension.class)
class NotificationExpirySweeperTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private LockProvider lockProvider;

    @Mock
    private SimpleLock simpleLock;

    private NotificationExpirySweeper sweeper;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        LockingTaskExecutor lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        NotificationTtlConfig ttlConfig = new NotificationTtlConfig(7, "dev",
            new NotificationTtlConfig.Sweep(
                true, 3_600_000, 5, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        sweeper = new NotificationExpirySweeper(notificationService, lockingTaskExecutor, ttlConfig);

        Logger logger = (Logger) LoggerFactory.getLogger(NotificationExpirySweeper.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationExpirySweeper.class);
        logger.detachAppender(logAppender);
    }

    @Nested
    class Sweep {

        @BeforeEach
        void acquireLock() {
            lenient().when(lockProvider.lock(any())).thenReturn(Optional.of(simpleLock));
        }

        @Test
        void shouldDeleteExpiredWithConfiguredBatchSize_whenLockAcquired() {
            when(notificationService.deleteExpired(5)).thenReturn(3);

            sweeper.sweep();

            verify(notificationService).deleteExpired(5);
            assertThat(logAppender.list)
                .anyMatch(event -> event.getFormattedMessage()
                    .equals("Expiry sweeper deleted 3 notification(s)"));
        }

        @Test
        void shouldAcquireExpirySweeperLock() {
            sweeper.sweep();

            ArgumentCaptor<LockConfiguration> captor =
                ArgumentCaptor.forClass(LockConfiguration.class);
            verify(lockProvider).lock(captor.capture());
            LockConfiguration lockConfiguration = captor.getValue();
            assertThat(lockConfiguration.getName()).isEqualTo(NotificationExpirySweeper.LOCK_NAME);
            assertThat(lockConfiguration.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(1));
            assertThat(lockConfiguration.getLockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void shouldNotDelete_whenLockNotAcquired() {
            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            sweeper.sweep();

            verify(notificationService, never()).deleteExpired(anyInt());
        }

        @Test
        void shouldNotLogDeletedCount_whenNothingExpired() {
            when(notificationService.deleteExpired(5)).thenReturn(0);

            sweeper.sweep();

            verify(notificationService).deleteExpired(5);
            assertThat(logAppender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("Expiry sweeper deleted"));
        }
    }
}
