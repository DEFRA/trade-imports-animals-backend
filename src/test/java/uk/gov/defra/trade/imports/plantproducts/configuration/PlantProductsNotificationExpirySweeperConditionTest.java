package uk.gov.defra.trade.imports.plantproducts.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationService;

class PlantProductsNotificationExpirySweeperConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withBean(PlantProductsNotificationService.class,
            () -> mock(PlantProductsNotificationService.class))
        .withBean(LockingTaskExecutor.class, () -> mock(LockingTaskExecutor.class))
        .withBean(PlantProductsNotificationTtlConfig.class,
            PlantProductsNotificationExpirySweeperConditionTest::ttlConfig)
        .withUserConfiguration(PlantProductsNotificationExpirySweeper.class);

    @Test
    void sweeper_shouldBeAbsentWhenPropertyIsUnset() {
        // When & Then
        runner.run(context -> assertThat(context)
            .doesNotHaveBean(PlantProductsNotificationExpirySweeper.class));
    }

    @Test
    void sweeper_shouldBeAbsentWhenPropertyIsFalse() {
        // When & Then
        runner.withPropertyValues("plant-products.notification.ttl.sweep.enabled=false")
            .run(context -> assertThat(context)
                .doesNotHaveBean(PlantProductsNotificationExpirySweeper.class));
    }

    @Test
    void sweeper_shouldBePresentWhenPropertyIsTrue() {
        // When & Then
        runner.withPropertyValues("plant-products.notification.ttl.sweep.enabled=true")
            .run(context -> assertThat(context)
                .hasSingleBean(PlantProductsNotificationExpirySweeper.class));
    }

    private static PlantProductsNotificationTtlConfig ttlConfig() {
        return new PlantProductsNotificationTtlConfig(
            7,
            "dev",
            new PlantProductsNotificationTtlConfig.Sweep(
                true,
                3_600_000,
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30)));
    }
}
