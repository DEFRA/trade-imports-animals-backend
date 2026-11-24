package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.assertOneLine;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.keyValues;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.totalDurationMillis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mongodb.MongoSocketReadException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandSucceededEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MongoCommandMetricsTest {

  private static final String DATABASE_NAME = "trade-imports-animals-backend";
  private static final ConnectionDescription CONNECTION =
      new ConnectionDescription(new ServerId(new ClusterId(), new ServerAddress()));

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MongoCommandMetrics listener =
      new MongoCommandMetrics(new ExternalCallRecorder(meterRegistry, new LastCallOutcomes()));

  @ParameterizedTest
  @ValueSource(
      strings = {
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
        "abortTransaction"
      })
  void commandSucceeded_recordsAKnownCommandUnderItsOwnName(String commandName) {
    listener.commandSucceeded(succeeded(commandName, 5));

    assertThat(durationCount(meterRegistry, mongodb(commandName))).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, mongodb(commandName))).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello", "killCursors"})
  void commandSucceeded_recordsAnUnknownCommandAsOther(String commandName) {
    listener.commandSucceeded(succeeded(commandName, 5));

    assertThat(durationCount(meterRegistry, mongodb("other"))).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, mongodb("other"))).isZero();
  }

  @Test
  void commandSucceeded_recordsTheElapsedTime() {
    listener.commandSucceeded(succeeded("find", 12));

    assertThat(totalDurationMillis(meterRegistry, mongodb("find"))).isEqualTo(12);
  }

  @Test
  void commandFailed_recordsTheElapsedTimeAndAFailureAndLogsTheCause() {
    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class,
            () ->
                listener.commandFailed(
                    new CommandFailedEvent(
                        null,
                        1,
                        1,
                        CONNECTION,
                        DATABASE_NAME,
                        "insert",
                        TimeUnit.MILLISECONDS.toNanos(3),
                        new MongoSocketReadException("closed", new ServerAddress()))));

    assertThat(totalDurationMillis(meterRegistry, mongodb("insert"))).isEqualTo(3);
    assertThat(failureTotal(meterRegistry, mongodb("insert"))).isEqualTo(1);
    ILoggingEvent line =
        assertOneLine(lines, Level.WARN, "External call mongodb/insert failure in 3 ms");
    assertThat(keyValues(line))
        .containsEntry("event.action", "mongodb/insert")
        .containsEntry("error.type", "com.mongodb.MongoSocketReadException")
        .containsEntry("error.message", "closed");
  }

  @ParameterizedTest
  @MethodSource("repliesWithErrors")
  void commandSucceeded_shouldRecordAReplyWithAnErrorAsAFailure(
      String commandName, String replyJson, String errorMessage) {
    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class,
            () ->
                listener.commandSucceeded(
                    succeeded(commandName, 4, BsonDocument.parse(replyJson))));

    assertThat(durationCount(meterRegistry, mongodb(commandName))).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, mongodb(commandName))).isEqualTo(1);
    ILoggingEvent line =
        assertOneLine(
            lines, Level.WARN, "External call mongodb/" + commandName + " failure in 4 ms");
    assertThat(keyValues(line))
        .containsEntry("event.outcome", "failure")
        .containsEntry("error.type", MongoReplyWriteException.class.getName())
        .containsEntry("error.message", errorMessage);
  }

  private static Stream<Arguments> repliesWithErrors() {
    return Stream.of(
        arguments(
            "insert",
            """
            {ok: 1, n: 0, writeErrors: [{index: 0, code: 11000,
              errmsg: "E11000 duplicate key error dup key: { referenceNumber: \\"DRAFT.GB.1\\" }"}]}
            """,
            "writeErrors codes [11000]"),
        arguments(
            "update",
            """
            {ok: 1, n: 1, writeConcernError: {code: 64, codeName: "WriteConcernFailed",
              errmsg: "waiting for replication timed out"}}
            """,
            "writeConcernError code 64 WriteConcernFailed"));
  }

  private static ExternalCall mongodb(String operation) {
    return new ExternalCall(Dependency.MONGODB, operation);
  }

  private static CommandSucceededEvent succeeded(String commandName, long elapsedMillis) {
    return succeeded(commandName, elapsedMillis, BsonDocument.parse("{ok: 1}"));
  }

  private static CommandSucceededEvent succeeded(
      String commandName, long elapsedMillis, BsonDocument reply) {
    return new CommandSucceededEvent(
        null,
        1,
        1,
        CONNECTION,
        DATABASE_NAME,
        commandName,
        reply,
        TimeUnit.MILLISECONDS.toNanos(elapsedMillis));
  }
}
