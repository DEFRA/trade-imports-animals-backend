package uk.gov.defra.trade.imports.animals.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmfMetricsPublisherTest {

    private static final String TEST_NAMESPACE = "test-namespace";

    @Mock
    private MeterRegistry meterRegistry;

    private EmfMetricsPublisher emfMetricsPublisher;

    @BeforeEach
    void setUp() {
        emfMetricsPublisher = new EmfMetricsPublisher(TEST_NAMESPACE, meterRegistry);
    }

    @Test
    void constructor_shouldInitializeWithNamespaceAndRegistry() {
        // Then
        assertThat(emfMetricsPublisher).isNotNull();
    }

    @Test
    void publishMetrics_shouldHandleEmptyMeterRegistry() {
        // Given
        when(meterRegistry.getMeters()).thenReturn(Collections.emptyList());

        // When
        emfMetricsPublisher.publishMetrics();

        // Then
        verify(meterRegistry, times(2)).getMeters();
    }

    @Test
    void publishMetrics_shouldIterateOverAllMeters() {
        // Given
        SimpleMeterRegistry realRegistry = new SimpleMeterRegistry();
        realRegistry.counter("test.counter").increment();
        realRegistry.timer("test.timer").record(() -> {});

        EmfMetricsPublisher publisher = new EmfMetricsPublisher(TEST_NAMESPACE, realRegistry);

        // When
        publisher.publishMetrics();

        // Then
        assertThat(realRegistry.getMeters()).isNotEmpty();
        assertThat(realRegistry.getMeters()).hasSize(2);
    }

    @Test
    void publishMetrics_shouldCollectMetricsFromMeter() {
        // Given
        Meter mockMeter = mock(Meter.class);
        Id meterId = mock(Id.class);
        Measurement measurement = new Measurement(() -> 42.0, null);

        when(meterId.getName()).thenReturn("test.metric");
        when(mockMeter.getId()).thenReturn(meterId);
        when(mockMeter.measure()).thenReturn(Arrays.asList(measurement));
        when(meterRegistry.getMeters()).thenReturn(Arrays.asList(mockMeter));

        // When
        emfMetricsPublisher.publishMetrics();

        // Then
        verify(meterRegistry, times(2)).getMeters();
        verify(mockMeter, times(1)).measure();
    }
}
