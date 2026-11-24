package uk.gov.defra.trade.imports.animals.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Registers the gauge {@code external.dependency.available}, tagged {@code dependency}, for each
 * dependency: 1 while it is available, 0 while it is not, and NaN while unknown.
 */
@Component
public class DependencyAvailabilityMetrics {

  DependencyAvailabilityMetrics(
      MeterRegistry meterRegistry,
      MongoAvailability mongoAvailability,
      LastCallOutcomes lastCallOutcomes) {
    register(
        meterRegistry,
        Dependency.MONGODB,
        mongoAvailability,
        availability -> gaugeValueOf(availability.state()));
    for (Dependency dependency : Dependency.values()) {
      if (dependency != Dependency.MONGODB) {
        register(
            meterRegistry,
            dependency,
            lastCallOutcomes,
            outcomes -> lastCallGaugeValueOf(outcomes, dependency));
      }
    }
  }

  private static <T> void register(
      MeterRegistry meterRegistry,
      Dependency dependency,
      T source,
      ToDoubleFunction<T> value) {
    Gauge.builder("external.dependency.available", source, value)
        .tag(ExternalCallRecorder.DEPENDENCY_TAG, dependency.tagValue())
        .register(meterRegistry);
  }

  private static double gaugeValueOf(MongoAvailability.State state) {
    return switch (state) {
      case AVAILABLE -> 1;
      case UNAVAILABLE -> 0;
      case UNKNOWN -> Double.NaN;
    };
  }

  private static double lastCallGaugeValueOf(LastCallOutcomes outcomes, Dependency dependency) {
    return outcomes
        .lastCallSucceeded(dependency)
        .map(succeeded -> succeeded ? 1.0 : 0.0)
        .orElse(Double.NaN);
  }
}
