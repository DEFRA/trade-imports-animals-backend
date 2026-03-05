package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uk.gov.defra.trade.imports.animals.service.EmfMetricsPublisher;

import java.lang.reflect.Method;

class MetricsConfigurationPropertiesTest {

    @Test
    void emfMetricsPublisher_shouldHaveConditionalOnPropertyAnnotation() {
        // Given
        Class<?> clazz = EmfMetricsPublisher.class;

        // When
        ConditionalOnProperty annotation = clazz.getAnnotation(ConditionalOnProperty.class);

        // Then
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).containsExactly("management.metrics.enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
    }

    @Test
    void emfMetricsPublisher_shouldBeAnnotatedAsService() {
        // Given
        Class<?> clazz = EmfMetricsPublisher.class;

        // When
        Service annotation = clazz.getAnnotation(Service.class);

        // Then
        assertThat(annotation).isNotNull();
    }

    @Test
    void publishMetrics_shouldBeScheduled() throws NoSuchMethodException {
        // Given
        Method method = EmfMetricsPublisher.class.getMethod("publishMetrics");

        // When
        Scheduled annotation = method.getAnnotation(Scheduled.class);

        // Then
        assertThat(annotation).isNotNull();
        assertThat(annotation.fixedRate()).isEqualTo(60000);
    }
}
