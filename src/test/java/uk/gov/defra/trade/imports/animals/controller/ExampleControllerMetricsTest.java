package uk.gov.defra.trade.imports.animals.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.defra.trade.imports.animals.domain.Example;
import uk.gov.defra.trade.imports.animals.service.ExampleService;

@ExtendWith(MockitoExtension.class)
class ExampleControllerMetricsTest {

    @Mock
    private ExampleService exampleService;

    @Mock
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private ExampleController exampleController;

    @BeforeEach
    void setUp() {
        exampleController = new ExampleController(exampleService, meterRegistry);
    }

    @Test
    void findAll_shouldHaveTimedAnnotation() throws NoSuchMethodException {
        // Given
        Method method = ExampleController.class.getMethod("findAll");

        // When
        Timed annotation = method.getAnnotation(Timed.class);

        // Then
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("controller.getAllExampleEntities.time");
    }

    @Test
    void findAll_shouldHaveCountedAnnotation() throws NoSuchMethodException {
        // Given
        Method method = ExampleController.class.getMethod("findAll");

        // When
        Counted annotation = method.getAnnotation(Counted.class);

        // Then
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("controller.getAllExampleEntities.count");
    }

    @Test
    void create_shouldHaveTimedAnnotation() throws NoSuchMethodException {
        // Given
        Method method = ExampleController.class.getMethod("create", Example.class);

        // When
        Timed annotation = method.getAnnotation(Timed.class);

        // Then
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("controller.postExampleEntity.time");
    }

    @Test
    void findAll_shouldCallService() {
        // Given
        List<Example> expectedList = Collections.emptyList();
        when(exampleService.findAll()).thenReturn(expectedList);

        // When
        List<Example> result = exampleController.findAll();

        // Then
        verify(exampleService).findAll();
        assertThat(result).isEqualTo(expectedList);
    }

    @Test
    void create_shouldCallService() {
        // Given
        Example example = new Example();
        example.setName("test");
        when(exampleService.create(any(Example.class))).thenReturn(example);

        // When
        Example result = exampleController.create(example);

        // Then
        verify(exampleService).create(example);
        assertThat(result).isEqualTo(example);
    }
}
