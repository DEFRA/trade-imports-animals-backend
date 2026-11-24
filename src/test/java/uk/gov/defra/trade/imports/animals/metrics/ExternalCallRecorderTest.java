package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.keyValues;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.recordedCallCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.totalDurationMillis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExternalCallRecorderTest {

  private static final ExternalCall PUBLISH = new ExternalCall(Dependency.SNS, "publish");
  private static final ExternalCall GET_OBJECT = new ExternalCall(Dependency.S3, "get-object");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LastCallOutcomes lastCallOutcomes = new LastCallOutcomes();
  private final AtomicLong nanoTime = new AtomicLong();
  private final ExternalCallRecorder recorder =
      new ExternalCallRecorder(meterRegistry, lastCallOutcomes, nanoTime::get);

  @Test
  void recordSuccess_shouldRecordTheDurationAZeroFailureAndTheDependencyAsSucceeded() {
    recorder.recordSuccess(PUBLISH, Duration.ofMillis(7));

    assertThat(durationCount(meterRegistry, PUBLISH)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, PUBLISH)).isEqualTo(7);
    assertThat(recordedCallCount(meterRegistry, PUBLISH)).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, PUBLISH)).isZero();
    assertThat(lastCallOutcomes.lastCallSucceeded(Dependency.SNS)).contains(true);
  }

  @Test
  void recordFailure_shouldRecordTheDurationAFailureOfOneAndTheDependencyAsFailed() {
    recorder.recordFailure(GET_OBJECT, Duration.ofMillis(3), new IllegalStateException());

    assertThat(durationCount(meterRegistry, GET_OBJECT)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, GET_OBJECT)).isEqualTo(3);
    assertThat(recordedCallCount(meterRegistry, GET_OBJECT)).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, GET_OBJECT)).isEqualTo(1);
    assertThat(lastCallOutcomes.lastCallSucceeded(Dependency.S3)).contains(false);
  }

  @Test
  void recordSuccess_shouldLogOneInfoLineWithTheEventFieldsAndANanosecondDuration() {
    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class, () -> recorder.recordSuccess(PUBLISH, Duration.ofMillis(2)));

    assertThat(lines).hasSize(1);
    ILoggingEvent line = lines.getFirst();
    assertThat(line.getLevel()).isEqualTo(Level.INFO);
    assertThat(line.getFormattedMessage()).isEqualTo("External call sns/publish success in 2 ms");
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
            () -> recorder.recordFailure(GET_OBJECT, Duration.ofMillis(4), cause));

    assertThat(lines).hasSize(1);
    ILoggingEvent line = lines.getFirst();
    assertThat(line.getLevel()).isEqualTo(Level.WARN);
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
