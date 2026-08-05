package uk.gov.defra.trade.imports.plantproducts.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "plant-products.notification.ttl")
public record PlantProductsNotificationTtlConfig(
    @Positive Integer days,
    String environment,
    @Valid @NotNull Sweep sweep) {

    public record Sweep(
        boolean enabled,
        long intervalMs,
        int batchSize,
        Duration lockAtLeastFor,
        Duration lockAtMostFor) {

        public Sweep {
            if (batchSize <= 0) {
                batchSize = 10;
            }
            if (intervalMs <= 0) {
                intervalMs = Duration.ofHours(1).toMillis();
            }
            if (lockAtLeastFor == null) {
                lockAtLeastFor = Duration.ofSeconds(1);
            }
            if (lockAtMostFor == null) {
                lockAtMostFor = Duration.ofSeconds(30);
            }
        }
    }

    public boolean isProd() {
        return "prod".equalsIgnoreCase(environment);
    }
}
