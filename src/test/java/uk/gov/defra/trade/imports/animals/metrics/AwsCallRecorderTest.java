package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.assertOneLine;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.keyValues;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.SNS_PUBLISH;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationSampleCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.totalDurationMillis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.sns.model.SnsException;

class AwsCallRecorderTest {

  private static final SnsException CAUSE =
      (SnsException) SnsException.builder().message("Topic does not exist").build();

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LastCallOutcomes lastCallOutcomes = new LastCallOutcomes();
  private final AtomicLong nanoTime = new AtomicLong();
  private final AwsCallRecorder snsRecorder =
      new AwsCallRecorder(
          new ExternalCallRecorder(meterRegistry, lastCallOutcomes, nanoTime::get),
          Dependency.SNS,
          Set.of("publish"));

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({"Publish, publish", "PublishBatch, other", "GetTopicAttributes, other"})
  void callOf_shouldBeTheActionInKebabCase_orOtherOutsideTheKnownSet(
      String action, String operation) {
    assertThat(snsRecorder.callOf(attributesFor(action)))
        .isEqualTo(new ExternalCall(Dependency.SNS, operation));
  }

  @Test
  void callOf_shouldBeOther_whenTheCallHasNoAction() {
    assertThat(snsRecorder.callOf(new ExecutionAttributes()).operation()).isEqualTo("other");
  }

  @Test
  void afterExecution_shouldRecordTheTimeSinceTheCallBeganAsASuccessOfItsDependency() {
    ExecutionAttributes attributes = attributesFor("Publish");

    snsRecorder.beforeExecution(null, attributes);
    nanoTime.addAndGet(Duration.ofMillis(40).toNanos());
    snsRecorder.afterExecution(null, attributes);

    assertThat(durationSampleCount(meterRegistry, SNS_PUBLISH)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, SNS_PUBLISH)).isEqualTo(40);
    assertThat(failureTotal(meterRegistry, SNS_PUBLISH)).isZero();
    assertThat(lastCallOutcomes.lastCallAvailability(Dependency.SNS))
        .isEqualTo(Availability.AVAILABLE);
  }

  @Test
  void onExecutionFailure_shouldRecordTheTimeSinceTheCallBeganAsAFailureOfItsDependency() {
    ExecutionAttributes attributes = attributesFor("Publish");

    snsRecorder.beforeExecution(null, attributes);
    nanoTime.addAndGet(Duration.ofMillis(25).toNanos());
    snsRecorder.onExecutionFailure(failedWith(CAUSE), attributes);

    assertThat(durationSampleCount(meterRegistry, SNS_PUBLISH)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, SNS_PUBLISH)).isEqualTo(25);
    assertThat(failureTotal(meterRegistry, SNS_PUBLISH)).isEqualTo(1);
    assertThat(lastCallOutcomes.lastCallAvailability(Dependency.SNS))
        .isEqualTo(Availability.UNAVAILABLE);
  }

  @Test
  void onExecutionFailure_shouldRecordAFailureWithNoDurationAndSaySo_whenTheCallNeverBegan() {
    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class,
            () -> snsRecorder.onExecutionFailure(failedWith(CAUSE), attributesFor("Publish")));

    assertThat(failureTotal(meterRegistry, SNS_PUBLISH)).isEqualTo(1);
    assertThat(durationSampleCount(meterRegistry, SNS_PUBLISH)).isZero();
    ILoggingEvent line =
        assertOneLine(
            lines, Level.WARN, "External call sns/publish failure with no start time recorded");
    assertThat(keyValues(line))
        .containsEntry("event.action", "sns/publish")
        .doesNotContainKey("event.duration");
  }

  private static ExecutionAttributes attributesFor(String action) {
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, action);
    return attributes;
  }

  private static Context.FailedExecution failedWith(Throwable cause) {
    Context.FailedExecution context = mock(Context.FailedExecution.class);
    when(context.exception()).thenReturn(cause);
    return context;
  }
}
