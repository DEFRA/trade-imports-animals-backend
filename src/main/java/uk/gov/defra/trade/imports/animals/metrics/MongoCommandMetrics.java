package uk.gov.defra.trade.imports.animals.metrics;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandSucceededEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records every MongoDB command as an external call to {@code mongodb}, its operation the command
 * name, or {@code other} for a command outside the fixed set. A command fails when it errors or
 * when its reply reports {@code writeErrors} or a {@code writeConcernError}.
 */
@Component
@RequiredArgsConstructor
public class MongoCommandMetrics implements CommandListener {

  private final ExternalCallRecorder externalCallRecorder;

  @Override
  public void commandSucceeded(CommandSucceededEvent event) {
    ExternalCall call = callOf(event.getCommandName());
    Duration elapsed = Duration.ofNanos(event.getElapsedTime(TimeUnit.NANOSECONDS));
    MongoReplyWriteException.reportedBy(event.getResponse())
        .ifPresentOrElse(
            writeFailure -> externalCallRecorder.recordFailure(call, elapsed, writeFailure),
            () -> externalCallRecorder.recordSuccess(call, elapsed));
  }

  @Override
  public void commandFailed(CommandFailedEvent event) {
    externalCallRecorder.recordFailure(
        callOf(event.getCommandName()),
        Duration.ofNanos(event.getElapsedTime(TimeUnit.NANOSECONDS)),
        event.getThrowable());
  }

  private static ExternalCall callOf(String commandName) {
    String operation =
        switch (commandName) {
          case "find",
              "getMore",
              "insert",
              "update",
              "delete",
              "findAndModify",
              "aggregate",
              "count",
              "distinct",
              "createIndexes",
              "listIndexes",
              "dropIndexes",
              "listCollections",
              "commitTransaction",
              "abortTransaction" ->
              commandName;
          default -> "other";
        };
    return new ExternalCall(Dependency.MONGODB, operation);
  }
}
