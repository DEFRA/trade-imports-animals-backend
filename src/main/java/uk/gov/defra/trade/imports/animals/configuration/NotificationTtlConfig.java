package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Positive;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The days used to stamp a notification's expiry and the running environment's name, bound to
 * {@code notification.ttl}.
 *
 * @param days        days after a notification's creation that its expiry is stamped for;
 *                    {@code null} when none is set. Must be positive when set
 * @param environment the running CDP environment name (from {@code ENVIRONMENT})
 */
@Validated
@ConfigurationProperties(prefix = "notification.ttl")
public record NotificationTtlConfig(@Nullable @Positive Integer days, String environment) {

  /** True when the running environment is production: {@code prod}, in any case. */
  public boolean isProd() {
    return "prod".equalsIgnoreCase(environment);
  }

  /**
   * The days after creation a new notification's expiry is stamped for.
   *
   * @return the configured days, or empty in prod or when no days are set
   */
  public Optional<Integer> daysToStamp() {
    if (isProd()) {
      return Optional.empty();
    }
    return Optional.ofNullable(days);
  }
}
