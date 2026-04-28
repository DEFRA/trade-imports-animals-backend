package uk.gov.defra.trade.imports.animals.configuration;

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
    UploaderConfig uploader,
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
  public record BackendConfig(String baseUrl) {}

  /**
   * Frontend service base URL configuration.
   *
   * <p>Kept as a distinct type from {@link BackendConfig} to provide compile-time type safety —
   * injection sites can declare exactly which URL they need and avoid accidental swaps.
   *
   * @param baseUrl the public base URL of the frontend service
   */
  public record FrontendConfig(String baseUrl) {}

  public record S3Config(String documentsBucket) {}

  public record MetricsConfig(boolean enabled) {}

  public record TracingConfig(String headerName) {}

  public record CloudwatchConfig(String endpoint) {}
}
