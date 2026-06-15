package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxPublishService outboxPublishService;

    @Mock
    private LockProvider lockProvider;

    @Mock
    private SimpleLock simpleLock;

    private OutboxPoller outboxPoller;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        LockingTaskExecutor lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        OutboxConfig properties = new OutboxConfig(
            new OutboxConfig.Poller(
                2000, 10, Duration.ofSeconds(1), Duration.ofSeconds(30), true),
            new OutboxConfig.Sns("arn:aws:sns:eu-west-2:000000000000:test-topic"));
        outboxPoller = new OutboxPoller(outboxPublishService, lockingTaskExecutor, properties);

        Logger logger = (Logger) LoggerFactory.getLogger(OutboxPoller.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxPoller.class);
        logger.detachAppender(logAppender);
    }

    @Nested
    class Poll {

        @BeforeEach
        void acquireLock() {
            lenient().when(lockProvider.lock(any()))
                .thenReturn(Optional.of(simpleLock));
        }

        @Test
        void shouldPublishUnpublishedEvents_whenLockAcquired() {
            // Given
            when(outboxPublishService.publishUnpublishedEvents()).thenReturn(2);

            // When
            outboxPoller.poll();

            // Then
            verify(outboxPublishService).publishUnpublishedEvents();
            assertThat(logAppender.list)
                .anyMatch(event -> event.getFormattedMessage()
                    .equals("Outbox poller published 2 event(s)"));
        }

        @Test
        void shouldAcquireOutboxPollerLock() {
            // When
            outboxPoller.poll();

            // Then
            ArgumentCaptor<LockConfiguration> captor = ArgumentCaptor.forClass(LockConfiguration.class);
            verify(lockProvider).lock(captor.capture());
            LockConfiguration lockConfiguration = captor.getValue();
            assertThat(lockConfiguration.getName()).isEqualTo(OutboxPoller.LOCK_NAME);
            assertThat(lockConfiguration.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(1));
            assertThat(lockConfiguration.getLockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void shouldNotPublish_whenLockNotAcquired() {
            // Given
            when(lockProvider.lock(any())).thenReturn(Optional.empty());

            // When
            outboxPoller.poll();

            // Then
            verify(outboxPublishService, never()).publishUnpublishedEvents();
        }

        @Test
        void shouldNotLogPublishedCount_whenNoEventsPublished() {
            // Given
            when(outboxPublishService.publishUnpublishedEvents()).thenReturn(0);

            // When
            outboxPoller.poll();

            // Then
            verify(outboxPublishService).publishUnpublishedEvents();
            assertThat(logAppender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("Outbox poller published"));
        }
    }
}
