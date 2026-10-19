package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Automatic expiry of app-created notifications outside prod, bound to {@code notification.ttl}.
 *
 * @param days        how many days after {@code created} a notification expires; {@code null}
 *                    disables expiry stamping. Must be positive when set
 * @param environment the running CDP environment name (from {@code ENVIRONMENT}); production is
 *                    {@code prod}, matched case-insensitively
 * @param sweep       expiry-sweeper settings
 */
@Validated
@ConfigurationProperties(prefix = "notification.ttl")
public record NotificationTtlConfig(
    @Positive Integer days,
    String environment,
    @Valid @NotNull Sweep sweep) {

  /**
   * Expiry-sweeper settings.
   *
   * @param enabled       whether the scheduled sweeper runs
   * @param intervalMs    delay between sweeps; default 1 hour
   * @param batchSize     max notifications deleted per sweep; default 10
   * @param lockAtLeastFor shedlock minimum hold
   * @param lockAtMostFor  shedlock maximum hold
   */
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

  /** True when the running environment is production, where expiry must never occur. */
  public boolean isProd() {
    return "prod".equalsIgnoreCase(environment);
  }
}
