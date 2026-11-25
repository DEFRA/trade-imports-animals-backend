package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for CDP platform integrations.
 *
 * <p>Bound to the {@code cdp} prefix in {@code application.yml}. Covers the CDP platform values
 * and S3 document storage.
 *
 * @param certificate    the CDP platform TLS certificate (PEM-encoded)
 * @param metrics        metrics-publishing configuration
 * @param serviceVersion the running service version, surfaced in metrics and traces
 * @param tracing        distributed tracing configuration
 * @param cloudwatch     CloudWatch logging endpoint configuration
 * @param proxyUrl       the outbound HTTP proxy URL used to reach CDP services
 * @param s3             S3 document storage configuration
 */
@Validated
@ConfigurationProperties(prefix = "cdp")
public record CdpConfig(
    String certificate,
    MetricsConfig metrics,
    String serviceVersion,
    TracingConfig tracing,
    CloudwatchConfig cloudwatch,
    String proxyUrl,
    @Valid @NotNull S3Config s3) {

  /**
   * S3 document storage configuration.
   *
   * @param documentsBucket the name of the S3 bucket used to store uploaded documents
   */
  public record S3Config(@NotBlank String documentsBucket) {}

  /**
   * Metrics-publishing configuration.
   *
   * @param enabled whether metrics publishing is enabled
   */
  public record MetricsConfig(boolean enabled) {}

  /**
   * Distributed tracing configuration.
   *
   * @param headerName the name of the HTTP header carrying the trace identifier
   */
  public record TracingConfig(String headerName) {}

  /**
   * CloudWatch logging endpoint configuration.
   *
   * @param endpoint the CloudWatch logs endpoint URL
   */
  public record CloudwatchConfig(String endpoint) {}
}
