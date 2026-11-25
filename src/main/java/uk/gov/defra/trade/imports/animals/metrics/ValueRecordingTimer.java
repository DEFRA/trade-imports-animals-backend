package uk.gov.defra.trade.imports.animals.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.step.StepTimer;
import io.micrometer.core.instrument.util.TimeUtils;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("java:S2160")
class ValueRecordingTimer extends StepTimer {

  private final RecordedValues recordedValues = new RecordedValues();

  ValueRecordingTimer(
      Id id,
      Clock clock,
      DistributionStatisticConfig distributionStatisticConfig,
      PauseDetector pauseDetector,
      TimeUnit baseTimeUnit,
      long stepMillis) {
    super(id, clock, distributionStatisticConfig, pauseDetector, baseTimeUnit, stepMillis, false);
  }

  @Override
  protected void recordNonNegative(long amount, TimeUnit unit) {
    super.recordNonNegative(amount, unit);
    recordedValues.add(TimeUtils.convert((double) amount, unit, baseTimeUnit()));
  }

  RecordedValues recordedValues() {
    return recordedValues;
  }
}
