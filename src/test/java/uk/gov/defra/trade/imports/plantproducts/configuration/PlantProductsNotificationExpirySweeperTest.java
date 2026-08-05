package uk.gov.defra.trade.imports.plantproducts.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationService;

@ExtendWith(MockitoExtension.class)
class PlantProductsNotificationExpirySweeperTest {

    @Mock
    private PlantProductsNotificationService notificationService;

    @Mock
    private LockingTaskExecutor lockingTaskExecutor;

    private PlantProductsNotificationExpirySweeper sweeper;

    @BeforeEach
    void setUp() {
        PlantProductsNotificationTtlConfig ttlConfig = new PlantProductsNotificationTtlConfig(
            7,
            "dev",
            new PlantProductsNotificationTtlConfig.Sweep(
                true,
                3_600_000,
                5,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30)));
        sweeper = new PlantProductsNotificationExpirySweeper(
            notificationService, lockingTaskExecutor, ttlConfig);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(
            any(Runnable.class), any(LockConfiguration.class));
    }

    @Nested
    class Sweep {

        @Test
        void sweep_shouldDeleteExpiredUsingConfiguredBatchBound() {
            // Given
            when(notificationService.deleteExpired(5)).thenReturn(2);

            // When
            sweeper.sweep();

            // Then
            verify(notificationService).deleteExpired(5);
        }

        @Test
        void sweep_shouldStillRunQueryWhenNoNotificationsAreExpired() {
            // Given
            when(notificationService.deleteExpired(5)).thenReturn(0);

            // When
            sweeper.sweep();

            // Then
            verify(notificationService).deleteExpired(5);
        }

        @Test
        void sweep_shouldRunWorkUnderNamedShedLock() {
            // When
            sweeper.sweep();

            // Then
            ArgumentCaptor<LockConfiguration> lock =
                ArgumentCaptor.forClass(LockConfiguration.class);
            verify(lockingTaskExecutor).executeWithLock(any(Runnable.class), lock.capture());
            assertThat(lock.getValue().getName())
                .isEqualTo(PlantProductsNotificationExpirySweeper.LOCK_NAME);
            assertThat(lock.getValue().getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(1));
            assertThat(lock.getValue().getLockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
        }
    }
}
