package uk.gov.defra.trade.imports.animals.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;

@Service
@Slf4j
@ConditionalOnProperty(name = "management.metrics.enabled", havingValue = "true")
public class EmfMetricsPublisher {
  private final String namespace;
  private final MeterRegistry meterRegistry;
  
  EmfMetricsPublisher(
      @Value("${aws.emf.namespace}") String namespace,
      MeterRegistry meterRegistry) {
    this.namespace = namespace;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(fixedRate = 60000)
  public void publishMetrics() {
    MetricsLogger metricsLogger = new MetricsLogger();
    metricsLogger.setNamespace(namespace);
    meterRegistry
        .getMeters()
        .forEach(
            meter -> meter
                .measure()
                .forEach(
                    measurement -> {
                      var name = meter.getId().getName();
                      var value = measurement.getValue();
                      log.trace("Publishing metrics for {} with a value of {}", name, value);
                      metricsLogger.putMetric(name, value);
                    }));
    meterRegistry.getMeters()
        .stream()
        .filter(meter -> meter.getId().getName().startsWith("controller"))
        .forEach(meterRegistry::remove);
    metricsLogger.flush();
  }
}
