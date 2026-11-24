package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.totalDurationMillis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.s3.model.S3Exception;

class AwsCallRecorderTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LastCallOutcomes lastCallOutcomes = new LastCallOutcomes();
  private final AtomicLong nanoTime = new AtomicLong();
  private final AwsCallRecorder s3Recorder =
      new AwsCallRecorder(
          new ExternalCallRecorder(meterRegistry, lastCallOutcomes, nanoTime::get),
          Dependency.S3,
          Set.of("get-object", "head-object"));

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({
    "GetObject, get-object",
    "HeadObject, head-object",
    "PutObject, other",
    "ListObjectsV2, other"
  })
  void operationOf_shouldBeTheActionInKebabCase_orOtherOutsideTheGivenSet(
      String action, String operation) {
    assertThat(s3Recorder.operationOf(attributesFor(action))).isEqualTo(operation);
  }

  @Test
  void operationOf_shouldBeOther_whenTheCallHasNoAction() {
    assertThat(s3Recorder.operationOf(new ExecutionAttributes())).isEqualTo("other");
  }

  @Test
  void afterExecution_shouldRecordTheTimeSinceTheCallBeganAsASuccessOfItsDependency() {
    ExecutionAttributes attributes = attributesFor("GetObject");

    s3Recorder.beforeExecution(null, attributes);
    nanoTime.addAndGet(Duration.ofMillis(40).toNanos());
    s3Recorder.afterExecution(null, attributes);

    ExternalCall getObject = new ExternalCall(Dependency.S3, "get-object");
    assertThat(durationCount(meterRegistry, getObject)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, getObject)).isEqualTo(40);
    assertThat(failureTotal(meterRegistry, getObject)).isZero();
    assertThat(lastCallOutcomes.lastCallSucceeded(Dependency.S3)).contains(true);
  }

  @Test
  void onExecutionFailure_shouldRecordAFailureOfItsDependency() {
    ExecutionAttributes attributes = attributesFor("GetObject");
    S3Exception cause = (S3Exception) S3Exception.builder().message("NoSuchKey").build();

    s3Recorder.beforeExecution(null, attributes);
    s3Recorder.onExecutionFailure(failedWith(cause), attributes);

    ExternalCall getObject = new ExternalCall(Dependency.S3, "get-object");
    assertThat(failureTotal(meterRegistry, getObject)).isEqualTo(1);
    assertThat(lastCallOutcomes.lastCallSucceeded(Dependency.S3)).contains(false);
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
