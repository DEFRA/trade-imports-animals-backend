package uk.gov.defra.trade.imports.animals.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Optional;

/**
 * Configuration for structured logging with ECS (Elastic Common Schema) format.
 * Sets up service version in MDC for inclusion in all log entries.
 */
@Configuration
public class LoggingConfig {

    private static final String MDC_SERVICE_VERSION = "service.version";

    @Value("${cdp.service-version}")
    private String serviceVersion;

    @PostConstruct
    public void setupServiceVersion() {
        // Set service version as system property so Logback can access it
        System.setProperty(MDC_SERVICE_VERSION, serviceVersion);
    }
}
