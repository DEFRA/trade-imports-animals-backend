package uk.gov.defra.trade.imports.animals.metrics;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * Records every call an AWS SDK client makes as one external call to its dependency, its retries
 * counted in its duration, named after its SDK action in kebab case ({@code GetObject} as {@code
 * get-object}), or {@code other} for an action outside the given set.
 */
public class AwsCallRecorder implements ExecutionInterceptor {

  private static final ExecutionAttribute<Long> STARTED_AT_NANOS =
      new ExecutionAttribute<>("awsCallStartedAtNanos");

  private final ExternalCallRecorder externalCallRecorder;
  private final Dependency dependency;
  private final Set<String> operations;

  /**
   * Creates a recorder for one client.
   *
   * @param dependency the system the client calls
   * @param operations the kebab-case actions recorded by name
   */
  public AwsCallRecorder(
      ExternalCallRecorder externalCallRecorder, Dependency dependency, Set<String> operations) {
    this.externalCallRecorder = externalCallRecorder;
    this.dependency = dependency;
    this.operations = Set.copyOf(operations);
  }

  @Override
  public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
    attributes.putAttribute(STARTED_AT_NANOS, externalCallRecorder.callStartedAt());
  }

  @Override
  public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
    externalCallRecorder.recordSuccess(callOf(attributes), elapsed(attributes));
  }

  @Override
  public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
    externalCallRecorder.recordFailure(callOf(attributes), elapsed(attributes), context.exception());
  }

  String operationOf(ExecutionAttributes attributes) {
    String action = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    if (action == null) {
      return "other";
    }
    String operation = action.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    return operations.contains(operation) ? operation : "other";
  }

  private ExternalCall callOf(ExecutionAttributes attributes) {
    return new ExternalCall(dependency, operationOf(attributes));
  }

  private Duration elapsed(ExecutionAttributes attributes) {
    Long startedAt = attributes.getAttribute(STARTED_AT_NANOS);
    return startedAt == null ? Duration.ZERO : externalCallRecorder.elapsedSince(startedAt);
  }
}
