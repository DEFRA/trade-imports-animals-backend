package uk.gov.defra.trade.imports.animals.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class DependencyAvailabilityMetrics {

  DependencyAvailabilityMetrics(
      MeterRegistry meterRegistry,
      MongoAvailability mongoAvailability,
      LastCallOutcomes lastCallOutcomes) {
    register(meterRegistry, Dependency.MONGODB, mongoAvailability::state);
    for (Dependency dependency : Dependency.values()) {
      if (dependency != Dependency.MONGODB) {
        register(
            meterRegistry, dependency, () -> lastCallOutcomes.lastCallAvailability(dependency));
      }
    }
  }

  private static void register(
      MeterRegistry meterRegistry, Dependency dependency, Supplier<Availability> availability) {
    Gauge.builder("external.dependency.available", () -> gaugeValueOf(availability.get()))
        .tag(ExternalCallRecorder.DEPENDENCY_TAG, dependency.tagValue())
        .register(meterRegistry);
  }

  private static double gaugeValueOf(Availability availability) {
    return switch (availability) {
      case AVAILABLE -> 1;
      case UNAVAILABLE -> 0;
      case UNKNOWN -> Double.NaN;
    };
  }
}
