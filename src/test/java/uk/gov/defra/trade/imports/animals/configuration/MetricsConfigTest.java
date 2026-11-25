package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.assertOneLine;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;

import ch.qos.logback.classic.Level;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.ConversionService;
import uk.gov.defra.trade.imports.animals.metrics.EmfMeterRegistry;

class MetricsConfigTest {

    private static final String OFF_LINE =
        "Not publishing metrics to CloudWatch: aws.emf.enabled is not true";

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

    private final MetricsConfig metricsConfig = new MetricsConfig();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void timedAspect_shouldRecordATimedCallInTheGivenRegistry() {
        Instrumented instrumented = proxiedWith(metricsConfig.timedAspect(meterRegistry));

        instrumented.timedCall();

        assertThat(meterRegistry.get("test.timed").timer().count()).isEqualTo(1);
    }

    @Test
    void countedAspect_shouldRecordACountedCallInTheGivenRegistry() {
        Instrumented instrumented = proxiedWith(metricsConfig.countedAspect(meterRegistry));

        instrumented.countedCall();

        assertThat(meterRegistry.get("test.counted").counter().count()).isEqualTo(1);
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
    void emfPublishingOffLog_shouldLogOneInfoLine_whenEmfIsNotEnabled() {
        contextRunner.run(
            context ->
                assertOneLine(
                    loggedBy(MetricsConfig.class, () -> runOffLog(context)), Level.INFO, OFF_LINE));
    }

    @Test
    void emfPublishingOffLog_shouldLogOneInfoLine_whenEmfEnabledIsNotABoolean() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=yes")
            .run(
                context -> {
                    assertThat(context).doesNotHaveBean(EmfMeterRegistry.class);
                    assertOneLine(
                        loggedBy(MetricsConfig.class, () -> runOffLog(context)),
                        Level.INFO,
                        OFF_LINE);
                });
    }

    @Test
    void emfPublishingOffLog_shouldLogNothing_whenEmfIsEnabled() {
        contextRunner
            .withPropertyValues("aws.emf.enabled=true")
            .run(
                context ->
                    assertThat(loggedBy(MetricsConfig.class, () -> runOffLog(context))).isEmpty());
    }

    private static void runOffLog(ConfigurableApplicationContext context) {
        try {
            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instrumented proxiedWith(Object aspect) {
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new Instrumented());
        proxyFactory.addAspect(aspect);
        return proxyFactory.getProxy();
    }

    static class Instrumented {

        @Timed("test.timed")
        public String timedCall() {
            return "timed";
        }

        @Counted("test.counted")
        public String countedCall() {
            return "counted";
        }
    }
}
