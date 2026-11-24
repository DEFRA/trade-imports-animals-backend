package uk.gov.defra.trade.imports.animals.testsupport;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCall;

public final class ExternalCallMeters {

  private ExternalCallMeters() {}

  public static long durationCount(MeterRegistry registry, ExternalCall call) {
    return duration(registry, call).map(Timer::count).orElse(0L);
  }

  public static double totalDurationMillis(MeterRegistry registry, ExternalCall call) {
    return duration(registry, call)
        .map(timer -> timer.totalTime(TimeUnit.MILLISECONDS))
        .orElse(0.0);
  }

  public static long recordedCallCount(MeterRegistry registry, ExternalCall call) {
    return failure(registry, call).map(DistributionSummary::count).orElse(0L);
  }

  public static double failureTotal(MeterRegistry registry, ExternalCall call) {
    return failure(registry, call).map(DistributionSummary::totalAmount).orElse(0.0);
  }

  public static double availability(MeterRegistry registry, Dependency dependency) {
    return registry
        .get("external.dependency.available")
        .tag("dependency", dependency.tagValue())
        .gauge()
        .value();
  }

  private static Optional<Timer> duration(MeterRegistry registry, ExternalCall call) {
    return Optional.ofNullable(
        registry.find("external.call.duration").tags(tagsOf(call)).timer());
  }

  private static Optional<DistributionSummary> failure(MeterRegistry registry, ExternalCall call) {
    return Optional.ofNullable(
        registry.find("external.call.failure").tags(tagsOf(call)).summary());
  }

  private static String[] tagsOf(ExternalCall call) {
    return new String[] {
      "dependency", call.dependency().tagValue(), "operation", call.operation()
    };
  }
}
