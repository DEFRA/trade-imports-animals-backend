package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

/**
 * Verifies the sweeping safeguard: the {@link NotificationExpirySweeper} bean exists only when
 * {@code notification.ttl.sweep.enabled=true} is set explicitly, and is absent otherwise (the prod
 * default). Uses {@link ApplicationContextRunner} so no MongoDB or full application context is
 * needed.
 */
class NotificationExpirySweeperConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withBean(NotificationService.class, () -> mock(NotificationService.class))
        .withBean(LockingTaskExecutor.class, () -> mock(LockingTaskExecutor.class))
        .withBean(NotificationTtlConfig.class, () -> new NotificationTtlConfig(7, "dev",
            new NotificationTtlConfig.Sweep(
                true, 3_600_000, 10, Duration.ofSeconds(1), Duration.ofSeconds(30))))
        .withUserConfiguration(NotificationExpirySweeper.class);

    @Test
    void sweeperBeanPresent_whenSweepEnabled() {
        runner.withPropertyValues("notification.ttl.sweep.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(NotificationExpirySweeper.class));
    }

    @Test
    void sweeperBeanAbsent_whenSweepDisabled() {
        runner.withPropertyValues("notification.ttl.sweep.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(NotificationExpirySweeper.class));
    }

    @Test
    void sweeperBeanAbsent_whenSweepPropertyMissing() {
        runner.run(context -> assertThat(context).doesNotHaveBean(NotificationExpirySweeper.class));
    }
}
