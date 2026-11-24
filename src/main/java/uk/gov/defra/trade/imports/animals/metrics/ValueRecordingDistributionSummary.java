package uk.gov.defra.trade.imports.animals.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.step.StepDistributionSummary;

@SuppressWarnings("java:S2160")
class ValueRecordingDistributionSummary extends StepDistributionSummary {

  private final RecordedValues recordedAmounts = new RecordedValues();

  ValueRecordingDistributionSummary(
      Id id,
      Clock clock,
      DistributionStatisticConfig distributionStatisticConfig,
      double scale,
      long stepMillis) {
    super(id, clock, distributionStatisticConfig, scale, stepMillis, false);
  }

  @Override
  protected void recordNonNegative(double amount) {
    super.recordNonNegative(amount);
    recordedAmounts.add(amount);
  }

  RecordedValues recordedAmounts() {
    return recordedAmounts;
  }
}
