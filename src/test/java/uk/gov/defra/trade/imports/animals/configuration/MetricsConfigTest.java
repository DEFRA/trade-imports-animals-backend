package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.ConversionService;
import uk.gov.defra.trade.imports.animals.metrics.EmfMeterRegistry;

class MetricsConfigTest {

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withUserConfiguration(MetricsConfig.class)
            .withBean(
                ConfigurableApplicationContext.CONVERSION_SERVICE_BEAN_NAME,
                ConversionService.class,
                ApplicationConversionService::getSharedInstance)
            .withBean(
                SimpleMeterRegistry.class, SimpleMeterRegistry::new, bean -> bean.setPrimary(true))
            .withPropertyValues(
                "aws.emf.namespace=trade-imports-animals-backend", "aws.emf.step=1m");

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

    @Test
    void emfMeterRegistry_shouldBeCreated_whenEmfIsEnabledWithNoEnvironment() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(EmfMeterRegistry.class));
    }

    @Test
    void emfMeterRegistry_shouldBeCreated_whenTheEnvironmentIsBlank() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=true", "aws.emf.environment=")
            .run(context -> assertThat(context).hasSingleBean(EmfMeterRegistry.class));
    }

    @Test
    void emfMeterRegistry_shouldStopStartUp_whenTheEnvironmentIsNotAnEmfEnvironment() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=true", "aws.emf.environment=NotAnEnvironment")
            .run(
                context ->
                    assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void emfMeterRegistry_shouldBeLeftOut_whenEmfIsDisabled() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=false")
            .run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmfMeterRegistry.class);
                });
    }

    @Test
    void emfPublishingOffLog_shouldLogThatMetricsAreNotPublished_whenEmfIsNotEnabled() {
        contextRunner.run(
            context -> {
                ApplicationRunner offLog = context.getBean(ApplicationRunner.class);

                List<ILoggingEvent> events =
                    loggedBy(MetricsConfig.class, () -> runWithNoArguments(offLog));

                assertThat(events)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                        "Not publishing metrics to CloudWatch: aws.emf.enabled is not true");
            });
    }

    @Test
    void emfPublishingOffLog_shouldBeCreated_whenEmfEnabledIsNotABoolean() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=yes")
            .run(
                context -> {
                    assertThat(context).doesNotHaveBean(EmfMeterRegistry.class);
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                });
    }

    @Test
    void emfPublishingOffLog_shouldBeLeftOut_whenEmfIsEnabled() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
    }

    private static void runWithNoArguments(ApplicationRunner runner) {
        try {
            runner.run(new DefaultApplicationArguments());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
