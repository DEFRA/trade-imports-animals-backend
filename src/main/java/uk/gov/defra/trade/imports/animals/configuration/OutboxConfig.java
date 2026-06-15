package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the transactional outbox SNS relay.
 *
 * <p>poll every 2 seconds, batch size 10.
 */
@Validated
@ConfigurationProperties(prefix = "outbox")
public record OutboxConfig(
    @Valid @NotNull Poller poller,
    @Valid @NotNull Sns sns) {

    public record Poller(
        @Positive long intervalMs,
        @Positive int batchSize,
        Duration lockAtLeastFor,
        Duration lockAtMostFor,
        boolean enabled) {

        public Poller {
            if (intervalMs <= 0) {
                throw new IllegalArgumentException("outbox.poller.interval-ms must be positive");
            }
            if (batchSize <= 0) {
                batchSize = 10;
            }
            if (lockAtLeastFor == null) {
                lockAtLeastFor = Duration.ofSeconds(1);
            }
            if (lockAtMostFor == null) {
                lockAtMostFor = Duration.ofSeconds(30);
            }
        }
    }

    public record Sns(@NotBlank String topicArn) {}
}
