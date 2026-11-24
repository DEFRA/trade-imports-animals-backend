package uk.gov.defra.trade.imports.animals.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramGauges;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;
import software.amazon.cloudwatchlogs.emf.model.DimensionSet;
import software.amazon.cloudwatchlogs.emf.model.Unit;

/**
 * Publishes every meter to CloudWatch as Embedded Metric Format, once a step, in one namespace. A
 * meter's tags are its only dimensions.
 *
 * <p>Timers and distribution summaries publish every value recorded since the last publish, timers
 * in milliseconds. Counters publish the last step's count. Gauges publish their value when it is
 * finite. A gauge or distribution summary publishes in the CloudWatch unit of its base unit, such as
 * Bytes for {@code bytes}. With no base unit, a distribution summary publishes as Count and a gauge
 * as None. Long task timers, function counters, function timers and time gauges are not published.
 * A meter that fails to publish is logged and the rest are still published.
 */
@Slf4j
public class EmfMeterRegistry extends StepMeterRegistry {

  private final Duration step;
  private final String namespace;
  private final Supplier<MetricsLogger> metricsLoggers;

  /**
   * Creates the registry. It publishes nothing until {@link #start} is called.
   *
   * @param namespace the CloudWatch namespace
   * @param step how often the registry publishes
   * @param clock the clock the meters step by
   * @param metricsLoggers supplies a fresh logger for each meter published
   */
  public EmfMeterRegistry(
      String namespace, Duration step, Clock clock, Supplier<MetricsLogger> metricsLoggers) {
    super(stepsOf(step), clock);
    this.step = step;
    this.namespace = namespace;
    this.metricsLoggers = metricsLoggers;
  }

  private static StepRegistryConfig stepsOf(Duration step) {
    return new StepRegistryConfig() {
      @Override
      public String prefix() {
        return "emf";
      }

      @Override
      public String get(String key) {
        return null;
      }

      @Override
      public Duration step() {
        return step;
      }
    };
  }

  @Override
  protected TimeUnit getBaseTimeUnit() {
    return TimeUnit.MILLISECONDS;
  }

  @Override
  protected Timer newTimer(
      Meter.Id id,
      DistributionStatisticConfig distributionStatisticConfig,
      PauseDetector pauseDetector) {
    Timer timer =
        new ValueRecordingTimer(
            id,
            clock,
            distributionStatisticConfig,
            pauseDetector,
            getBaseTimeUnit(),
            step.toMillis());
    HistogramGauges.registerWithCommonFormat(timer, this);
    return timer;
  }

  @Override
  protected DistributionSummary newDistributionSummary(
      Meter.Id id, DistributionStatisticConfig distributionStatisticConfig, double scale) {
    DistributionSummary summary =
        new ValueRecordingDistributionSummary(
            id, clock, distributionStatisticConfig, scale, step.toMillis());
    HistogramGauges.registerWithCommonFormat(summary, this);
    return summary;
  }

  @Override
  protected void publish() {
    for (Meter meter : getMeters()) {
      try {
        publishReadings(meter, readingsOf(meter));
      } catch (RuntimeException e) {
        log.warn("Could not publish meter {}", meter.getId().getName(), e);
      }
    }
  }

  private record Readings(List<Double> values, Unit unit) {
    static final Readings NONE = new Readings(List.of(), Unit.NONE);
  }

  private static Readings readingsOf(Meter meter) {
    return switch (meter) {
      case ValueRecordingTimer timer ->
          new Readings(millisOf(timer.recordedNanos().takeAll()), Unit.MILLISECONDS);
      case ValueRecordingDistributionSummary summary ->
          new Readings(summary.recordedAmounts().takeAll(), unitOf(summary.getId(), Unit.COUNT));
      case Counter counter -> new Readings(List.of(counter.count()), Unit.COUNT);
      case TimeGauge _ -> Readings.NONE;
      case Gauge gauge -> new Readings(finiteValueOf(gauge), unitOf(gauge.getId(), Unit.NONE));
      default -> Readings.NONE;
    };
  }

  private static Unit unitOf(Meter.Id id, Unit withoutBaseUnit) {
    String baseUnit = id.getBaseUnit();
    if (baseUnit == null || baseUnit.isBlank()) {
      return withoutBaseUnit;
    }
    return BaseUnits.BYTES.equals(baseUnit) ? Unit.BYTES : Unit.NONE;
  }

  private static List<Double> millisOf(List<Double> nanos) {
    return nanos.stream().map(each -> each / TimeUnit.MILLISECONDS.toNanos(1)).toList();
  }

  private static List<Double> finiteValueOf(Gauge gauge) {
    double value = gauge.value();
    return Double.isFinite(value) ? List.of(value) : List.of();
  }

  private void publishReadings(Meter meter, Readings readings) {
    if (readings.values().isEmpty()) {
      return;
    }
    MetricsLogger metricsLogger = metricsLoggers.get();
    metricsLogger.setNamespace(namespace);
    metricsLogger.setDimensions(false, dimensionsOf(meter.getId()));
    String name = meter.getId().getName();
    readings.values().forEach(value -> metricsLogger.putMetric(name, value, readings.unit()));
    metricsLogger.flush();
  }

  private static DimensionSet dimensionsOf(Meter.Id id) {
    DimensionSet dimensions = new DimensionSet();
    for (Tag tag : id.getTagsAsIterable()) {
      dimensions.addDimension(tag.getKey(), tag.getValue());
    }
    return dimensions;
  }
}
