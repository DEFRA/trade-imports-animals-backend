package uk.gov.defra.trade.imports.animals.metrics;

import java.util.Locale;
import java.util.Set;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * Records every call an AWS SDK client makes as one external call to its dependency, its retries
 * counted in its duration, named after its SDK action in kebab case ({@code Publish} as {@code
 * publish}), or {@code other} for an action outside the given set. A failed call with no recorded
 * start time is recorded with no duration.
 */
public class AwsCallRecorder implements ExecutionInterceptor {

  private static final ExecutionAttribute<Long> STARTED_AT_NANOS =
      new ExecutionAttribute<>("awsCallStartedAtNanos");

  private final ExternalCallRecorder externalCallRecorder;
  private final Dependency dependency;
  private final Set<String> knownOperations;

  public AwsCallRecorder(
      ExternalCallRecorder externalCallRecorder,
      Dependency dependency,
      Set<String> knownOperations) {
    this.externalCallRecorder = externalCallRecorder;
    this.dependency = dependency;
    this.knownOperations = Set.copyOf(knownOperations);
  }

  @Override
  public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
    attributes.putAttribute(STARTED_AT_NANOS, externalCallRecorder.callStartedAt());
  }

  @Override
  public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
    externalCallRecorder.recordSuccess(
        callOf(attributes),
        externalCallRecorder.elapsedSince(attributes.getAttribute(STARTED_AT_NANOS)));
  }

  @Override
  public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
    ExternalCall call = callOf(attributes);
    Long startedAt = attributes.getAttribute(STARTED_AT_NANOS);
    if (startedAt == null) {
      externalCallRecorder.recordUntimedFailure(call, context.exception());
    } else {
      externalCallRecorder.recordFailure(
          call, externalCallRecorder.elapsedSince(startedAt), context.exception());
    }
  }

  ExternalCall callOf(ExecutionAttributes attributes) {
    String action = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    String operation =
        action == null
            ? null
            : action.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    return ExternalCall.of(dependency, operation, knownOperations);
  }
}
