package uk.gov.defra.trade.imports.animals.metrics;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoServerException;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandSucceededEvent;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records every MongoDB command as an external call to {@code mongodb}, its operation the command
 * name, or {@code other} for a command outside the fixed set. A command fails when it errors with
 * anything but a duplicate key, or when its reply reports a {@code writeConcernError} or a write
 * error other than a duplicate key.
 */
@Component
@RequiredArgsConstructor
public class MongoCallRecorder implements CommandListener {

  private static final Set<String> KNOWN_COMMANDS =
      Set.of(
          "find",
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
          "abortTransaction");

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
    ExternalCall call = callOf(event.getCommandName());
    Duration elapsed = Duration.ofNanos(event.getElapsedTime(TimeUnit.NANOSECONDS));
    if (isDuplicateKey(event.getThrowable())) {
      externalCallRecorder.recordSuccess(call, elapsed);
    } else {
      externalCallRecorder.recordFailure(call, elapsed, event.getThrowable());
    }
  }

  static boolean isDuplicateKey(int errorCode) {
    return ErrorCategory.fromErrorCode(errorCode) == ErrorCategory.DUPLICATE_KEY;
  }

  private static boolean isDuplicateKey(Throwable failure) {
    return failure instanceof MongoServerException serverFailure
        && isDuplicateKey(serverFailure.getCode());
  }

  private static ExternalCall callOf(String commandName) {
    return ExternalCall.of(Dependency.MONGODB, commandName, KNOWN_COMMANDS);
  }
}
