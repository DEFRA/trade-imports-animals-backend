package uk.gov.defra.trade.imports.animals.configuration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for CDP platform integrations.
 *
 * <p>Bound to the {@code cdp} prefix in {@code application.yml}. Covers the cdp-uploader service,
 * S3 document storage, and the backend's own public base URL (used for callback construction).
 */
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
    S3Config s3) {

  public record UploaderConfig(String baseUrl, Long maxFileSize, List<String> mimeTypes) {}

  public record BackendConfig(String baseUrl) {}

  public record S3Config(String documentsBucket) {}

  public record MetricsConfig(boolean enabled) {}

  public record TracingConfig(String headerName) {}

  public record CloudwatchConfig(String endpoint) {}
}
