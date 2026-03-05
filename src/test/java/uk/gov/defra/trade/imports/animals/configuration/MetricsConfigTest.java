package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsConfigTest {

    private MetricsConfig metricsConfig;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        metricsConfig = new MetricsConfig();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void timedAspect_shouldCreateTimedAspectWithRegistry() {
        // When
        TimedAspect result = metricsConfig.timedAspect(meterRegistry);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(TimedAspect.class);
    }

    @Test
    void countedAspect_shouldCreateCountedAspectWithRegistry() {
        // When
        CountedAspect result = metricsConfig.countedAspect(meterRegistry);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(CountedAspect.class);
    }
}
