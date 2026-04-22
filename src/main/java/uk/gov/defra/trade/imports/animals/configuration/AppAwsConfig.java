package uk.gov.defra.trade.imports.animals.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for application-level AWS overrides.
 *
 * <p>Bound to the {@code app.aws} prefix in {@code application.yml}. Used to
 * configure LocalStack endpoint and static credentials for local development
 * and integration tests; in deployed environments all fields default to empty
 * strings and {@link AwsConfig} falls back to
 * {@code DefaultCredentialsProvider}.
 *
 * @param endpointOverride optional S3 endpoint override (e.g. LocalStack URL)
 * @param accessKeyId optional static AWS access key ID
 * @param secretAccessKey optional static AWS secret access key
 */
@ConfigurationProperties(prefix = "app.aws")
public record AppAwsConfig(
    String endpointOverride,
    String accessKeyId,
    String secretAccessKey) { }
