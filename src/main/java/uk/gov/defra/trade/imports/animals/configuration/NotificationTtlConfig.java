package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for automatic expiry of app-created notifications in non-prod environments
 * (EUDPA-273).
 *
 * <p>Prod is protected by two independent safeguards. Deleting a notification in prod would require
 * both to fail together:
 *
 * <ul>
 *   <li><b>Stamping guard</b> — {@code expireAt} is stamped at creation only when {@link #days} is
 *       configured (non-prod config sets it; prod leaves it unset) <em>and</em> an explicit code
 *       check confirms {@link #environment} is not {@code prod}.
 *   <li><b>Sweeping guard</b> — the scheduled sweeper bean only exists where
 *       {@code notification.ttl.sweep.enabled=true} (non-prod). Prod leaves it {@code false}.
 * </ul>
 *
 * <p>Bound to the {@code notification.ttl} prefix. Defaults in {@code application.yml} are the
 * prod-safe values ({@link #days} unset, {@link Sweep#enabled} false); non-prod environments opt in
 * via {@code NOTIFICATION_TTL_DAYS} and {@code NOTIFICATION_TTL_SWEEP_ENABLED}.
 *
 * @param days        how many days after {@code created} a notification expires; {@code null} when
 *                    unconfigured, which disables stamping (the prod default)
 * @param environment the running CDP environment name (from {@code ENVIRONMENT}); production is
 *                    exactly {@code prod}
 * @param sweep       expiry-sweeper settings
 */
@Validated
@ConfigurationProperties(prefix = "notification.ttl")
public record NotificationTtlConfig(
    Integer days,
    String environment,
    @Valid @NotNull Sweep sweep) {

  /**
   * Expiry-sweeper settings.
   *
   * @param enabled       whether the scheduled sweeper runs (non-prod only); prod default false
   * @param intervalMs    delay between sweeps; coarse (default 1h) — 7-day granularity needs no
   *                      frequent polling
   * @param batchSize     max notifications deleted per sweep, to avoid large delete bursts
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
