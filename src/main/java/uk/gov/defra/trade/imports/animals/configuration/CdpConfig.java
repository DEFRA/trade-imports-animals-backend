package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for CDP platform integrations.
 *
 * <p>Bound to the {@code cdp} prefix in {@code application.yml}. Covers the cdp-uploader service,
 * S3 document storage, and the backend's own public base URL (used for callback construction).
 *
 * @param certificate    the CDP platform TLS certificate (PEM-encoded)
 * @param metrics        metrics-publishing configuration
 * @param serviceVersion the running service version, surfaced in metrics and traces
 * @param tracing        distributed tracing configuration
 * @param cloudwatch     CloudWatch logging endpoint configuration
 * @param proxyUrl       the outbound HTTP proxy URL used to reach CDP services
 * @param uploader       cdp-uploader service configuration; required
 * @param backend        backend service base URL configuration
 * @param frontend       frontend service base URL configuration
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
    @NotNull UploaderConfig uploader,
    BackendConfig backend,
    FrontendConfig frontend,
    S3Config s3) {

  /**
   * Configuration for the CDP uploader service.
   *
   * @param baseUrl     the base URL of the cdp-uploader endpoint
   * @param maxFileSize the maximum permitted file size in bytes; must be a positive value
   * @param mimeTypes   the list of permitted MIME types for uploads
   */
  public record UploaderConfig(
      String baseUrl,
      @NotNull @Positive Long maxFileSize,
      List<String> mimeTypes) {}

  /**
   * Backend service base URL configuration.
   *
   * <p>Kept as a distinct type from {@link FrontendConfig} to provide compile-time type safety —
   * injection sites can declare exactly which URL they need and avoid accidental swaps.
   *
   * @param baseUrl the public base URL of the backend service, used for callback construction
   */
  public record BackendConfig(@NotBlank String baseUrl) {}

  /**
   * Frontend service base URL configuration.
   *
   * <p>Kept as a distinct type from {@link BackendConfig} to provide compile-time type safety —
   * injection sites can declare exactly which URL they need and avoid accidental swaps.
   *
   * @param baseUrl the public base URL of the frontend service
   */
  public record FrontendConfig(@NotBlank String baseUrl) {}

  /**
   * S3 document storage configuration.
   *
   * @param documentsBucket the name of the S3 bucket used to store uploaded documents
   */
  public record S3Config(String documentsBucket) {}

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
