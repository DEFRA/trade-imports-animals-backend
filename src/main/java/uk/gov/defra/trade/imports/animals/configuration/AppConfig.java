package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level configuration properties.
 *
 * <p>Bound to the {@code app} prefix in {@code application.yml}.
 *
 * @param baseUrl the backend's own public base URL, used for callback and location-header
 *                construction; must not be blank
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppConfig(@NotBlank String baseUrl) {}
