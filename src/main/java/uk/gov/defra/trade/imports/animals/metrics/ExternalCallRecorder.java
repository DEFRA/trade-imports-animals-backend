package uk.gov.defra.trade.imports.animals.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Records each call to a dependency as the timer {@code external.call.duration} and the
 * distribution summary {@code external.call.failure} (1 for a failure, 0 for a success), both
 * tagged {@code dependency} and {@code operation}, logs it with the ECS {@code event.*} fields, and
 * records its outcome as the dependency's last call outcome. A failure's line also carries the ECS
 * {@code error.type} and {@code error.message} fields.
 */
@Slf4j
@Component
public class ExternalCallRecorder {

  static final String DEPENDENCY_TAG = "dependency";

  private final MeterRegistry meterRegistry;
  private final LastCallOutcomes lastCallOutcomes;
  private final LongSupplier nanoTime;

  @Autowired
  public ExternalCallRecorder(MeterRegistry meterRegistry, LastCallOutcomes lastCallOutcomes) {
    this(meterRegistry, lastCallOutcomes, System::nanoTime);
  }

  /**
   * Creates a recorder that times calls by the given clock.
   *
   * @param nanoTime the current time in nanoseconds, as {@link System#nanoTime()} gives it
   */
  public ExternalCallRecorder(
      MeterRegistry meterRegistry, LastCallOutcomes lastCallOutcomes, LongSupplier nanoTime) {
    this.meterRegistry = meterRegistry;
    this.lastCallOutcomes = lastCallOutcomes;
    this.nanoTime = nanoTime;
  }

  /**
   * The time a call starts, for {@link #elapsedSince(long)} once it ends.
   *
   * @return the current time in nanoseconds
   */
  public long callStartedAt() {
    return nanoTime.getAsLong();
  }

  /**
   * How long a call has taken.
   *
   * @param callStartedAt when the call started, from {@link #callStartedAt()}
   * @return the time since then
   */
  public Duration elapsedSince(long callStartedAt) {
    return Duration.ofNanos(nanoTime.getAsLong() - callStartedAt);
  }

  /**
   * Records a call that succeeded.
   *
   * @param call the call made
   * @param elapsed how long the call took
   */
  public void recordSuccess(ExternalCall call, Duration elapsed) {
    durationOf(call).record(elapsed);
    failureOf(call).record(0);
    logCall(Level.INFO, call, "success", elapsed, null);
    lastCallOutcomes.recordSuccess(call.dependency());
  }

  /**
   * Records a call that failed.
   *
   * @param call the call made
   * @param elapsed how long the call took
   * @param cause why it failed
   */
  public void recordFailure(ExternalCall call, Duration elapsed, Throwable cause) {
    durationOf(call).record(elapsed);
    failureOf(call).record(1);
    logCall(Level.WARN, call, "failure", elapsed, cause);
    lastCallOutcomes.recordFailure(call.dependency());
  }

  /**
   * Records a call that failed with no known start time. No duration is recorded or logged, and its
   * line says the start time is missing.
   *
   * @param call the call made
   * @param cause why it failed
   */
  public void recordUntimedFailure(ExternalCall call, Throwable cause) {
    failureOf(call).record(1);
    String action = actionOf(call);
    eventLine(Level.WARN, action, "failure", cause)
        .log("External call {} failure with no start time recorded", action);
    lastCallOutcomes.recordFailure(call.dependency());
  }

  private Timer durationOf(ExternalCall call) {
    return Timer.builder("external.call.duration").tags(tagsOf(call)).register(meterRegistry);
  }

  private DistributionSummary failureOf(ExternalCall call) {
    return DistributionSummary.builder("external.call.failure")
        .tags(tagsOf(call))
        .register(meterRegistry);
  }

  private static Tags tagsOf(ExternalCall call) {
    return Tags.of(DEPENDENCY_TAG, call.dependency().tagValue(), "operation", call.operation());
  }

  private static void logCall(
      Level level, ExternalCall call, String outcome, Duration elapsed, Throwable cause) {
    String action = actionOf(call);
    eventLine(level, action, outcome, cause)
        .addKeyValue("event.duration", elapsed.toNanos())
        .log("External call {} {} in {} ms", action, outcome, elapsed.toMillis());
  }

  private static LoggingEventBuilder eventLine(
      Level level, String action, String outcome, Throwable cause) {
    LoggingEventBuilder line =
        log.atLevel(level)
            .addKeyValue("event.category", "external-call")
            .addKeyValue("event.action", action)
            .addKeyValue("event.outcome", outcome);
    if (cause != null) {
      line.addKeyValue("error.type", cause.getClass().getName())
          .addKeyValue("error.message", cause.getMessage());
    }
    return line;
  }

  private static String actionOf(ExternalCall call) {
    return call.dependency().tagValue() + "/" + call.operation();
  }
}
