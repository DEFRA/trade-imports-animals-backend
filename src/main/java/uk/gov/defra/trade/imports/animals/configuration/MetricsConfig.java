package uk.gov.defra.trade.imports.animals.configuration;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Micrometer metrics.
 * <p>
 * Micrometer provides standard JVM, HTTP, and database metrics via Spring Boot Actuator.
 * Custom business metrics use AWS EMF (see EmfMetricsConfig and MetricsService).
 * <p>
 * ARCHITECTURE:
 * - Standard metrics (JVM, HTTP, DB): Micrometer via Spring Boot Actuator
 * - Custom business metrics: AWS Embedded Metrics Format (EMF)
 * <p>
 * This configuration only provides a fallback SimpleMeterRegistry when metrics are disabled.
 * When enabled, Spring Boot Actuator auto-configures appropriate registries.
 */
@Slf4j
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        log.debug("Creating TimedAspect for {}", meterRegistry.getClass().getSimpleName());
        return new TimedAspect(meterRegistry);
    }

    @Bean
    CountedAspect countedAspect(MeterRegistry registry) {
        return new CountedAspect(registry);
    }
 
}
