package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.assertOneLine;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.keyValues;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.S3_GET_OBJECT;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.SNS_PUBLISH;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationSampleCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureSampleCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.totalDurationMillis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExternalCallRecorderTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LastCallOutcomes lastCallOutcomes = new LastCallOutcomes();
  private final AtomicLong nanoTime = new AtomicLong();
  private final ExternalCallRecorder recorder =
      new ExternalCallRecorder(meterRegistry, lastCallOutcomes, nanoTime::get);

  @Test
  void recordSuccess_shouldRecordTheDurationAZeroFailureAndTheDependencyAsAvailable() {
    recorder.recordSuccess(SNS_PUBLISH, Duration.ofMillis(7));

    assertThat(durationSampleCount(meterRegistry, SNS_PUBLISH)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, SNS_PUBLISH)).isEqualTo(7);
    assertThat(failureSampleCount(meterRegistry, SNS_PUBLISH)).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, SNS_PUBLISH)).isZero();
    assertThat(lastCallOutcomes.lastCallAvailability(Dependency.SNS))
        .isEqualTo(Availability.AVAILABLE);
  }

  @Test
  void recordFailure_shouldRecordTheDurationAFailureOfOneAndTheDependencyAsUnavailable() {
    recorder.recordFailure(S3_GET_OBJECT, Duration.ofMillis(3), new IllegalStateException());

    assertThat(durationSampleCount(meterRegistry, S3_GET_OBJECT)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, S3_GET_OBJECT)).isEqualTo(3);
    assertThat(failureSampleCount(meterRegistry, S3_GET_OBJECT)).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, S3_GET_OBJECT)).isEqualTo(1);
    assertThat(lastCallOutcomes.lastCallAvailability(Dependency.S3))
        .isEqualTo(Availability.UNAVAILABLE);
  }

  @Test
  void recordSuccess_shouldLogOneInfoLineWithTheEventFieldsAndANanosecondDuration() {
    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class, () -> recorder.recordSuccess(SNS_PUBLISH, Duration.ofMillis(2)));

    ILoggingEvent line =
        assertOneLine(lines, Level.INFO, "External call sns/publish success in 2 ms");
    assertThat(keyValues(line))
        .containsEntry("event.category", "external-call")
        .containsEntry("event.action", "sns/publish")
        .containsEntry("event.outcome", "success")
        .containsEntry("event.duration", 2_000_000L);
  }

  @Test
  void recordFailure_shouldLogOneWarnLineWithTheErrorTypeAndMessageButNoStackTrace() {
    IllegalStateException cause = new IllegalStateException("The bucket does not exist");

    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class,
            () -> recorder.recordFailure(S3_GET_OBJECT, Duration.ofMillis(4), cause));

    ILoggingEvent line =
        assertOneLine(lines, Level.WARN, "External call s3/get-object failure in 4 ms");
    assertThat(keyValues(line))
        .containsEntry("event.action", "s3/get-object")
        .containsEntry("event.outcome", "failure")
        .containsEntry("error.type", "java.lang.IllegalStateException")
        .containsEntry("error.message", "The bucket does not exist");
    assertThat(line.getThrowableProxy()).isNull();
  }

  @Test
  void elapsedSince_shouldBeTheTimeSinceCallStartedAt() {
    long startedAt = recorder.callStartedAt();
    nanoTime.addAndGet(Duration.ofMillis(12).toNanos());

    assertThat(recorder.elapsedSince(startedAt)).isEqualTo(Duration.ofMillis(12));
  }
}
