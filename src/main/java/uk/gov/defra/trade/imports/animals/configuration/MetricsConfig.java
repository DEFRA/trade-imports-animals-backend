package uk.gov.defra.trade.imports.animals.configuration;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.cloudwatchlogs.emf.config.EnvironmentConfigurationProvider;
import software.amazon.cloudwatchlogs.emf.environment.Environments;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;
import uk.gov.defra.trade.imports.animals.metrics.EmfMeterRegistry;

@Slf4j
@Configuration
public class MetricsConfig {

  @Bean
  @ConditionalOnProperty(name = "aws.emf.enabled", havingValue = "true")
  EmfMeterRegistry emfMeterRegistry(
      @Value("${aws.emf.namespace}") String namespace,
      @Value("${aws.emf.step}") Duration step,
      @Value("${aws.emf.environment:}") String environment) {
    if (!environment.isBlank()) {
      EnvironmentConfigurationProvider.getConfig()
          .setEnvironmentOverride(Environments.valueOf(environment));
      log.info("Writing metrics to the {} EMF environment", environment);
    }
    EmfMeterRegistry emfRegistry =
        new EmfMeterRegistry(namespace, step, Clock.SYSTEM, MetricsLogger::new);
    emfRegistry.start(new NamedThreadFactory("emf-meter-registry"));
    log.info("Publishing metrics to CloudWatch namespace {} every {}", namespace, step);
    return emfRegistry;
  }

  @Bean
  @ConditionalOnExpression("!'${aws.emf.enabled:false}'.equalsIgnoreCase('true')")
  ApplicationRunner emfPublishingOffLog() {
    return args -> log.info("Not publishing metrics to CloudWatch: aws.emf.enabled is not true");
  }

  @Bean
  TimedAspect timedAspect(MeterRegistry meterRegistry) {
    log.debug("Creating TimedAspect for {}", meterRegistry.getClass().getSimpleName());
    return new TimedAspect(meterRegistry);
  }

  @Bean
  CountedAspect countedAspect(MeterRegistry meterRegistry) {
    return new CountedAspect(meterRegistry);
  }
}
