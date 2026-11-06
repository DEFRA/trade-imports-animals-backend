package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Automatic expiry of app-created notifications outside prod, bound to {@code notification.ttl}.
 *
 * @param days        how many days after {@code created} a notification expires; {@code null}
 *                    disables expiry stamping. Must be positive when set
 * @param environment the running CDP environment name (from {@code ENVIRONMENT}); production is
 *                    {@code prod}, matched case-insensitively
 */
@Validated
@ConfigurationProperties(prefix = "notification.ttl")
public record NotificationTtlConfig(@Positive Integer days, String environment) {

  /** True when the running environment is production, where expiry must never occur. */
  public boolean isProd() {
    return "prod".equalsIgnoreCase(environment);
  }
}
