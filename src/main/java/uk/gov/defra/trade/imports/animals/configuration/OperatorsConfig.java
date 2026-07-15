package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the operators service client.
 *
 * <p>Bound to the {@code operators} prefix. {@code url} comes from {@code
 * TRADE_IMPORTS_OPERATORS_URL} — the same environment variable name the frontend and the workspace
 * stack use for the operators service, so there is one name everywhere.
 *
 * @param url the base URL of the operators service; required
 */
@Validated
@ConfigurationProperties(prefix = "operators")
public record OperatorsConfig(@NotBlank String url) {}
