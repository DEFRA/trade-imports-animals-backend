package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole.captureSystemOut;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCall;
import uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole;

class ExternalCallRecorderIT extends IntegrationBase {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ExternalCall MONGODB_INSERT = new ExternalCall(Dependency.MONGODB, "insert");
  private static final ExternalCall MONGODB_FIND = new ExternalCall(Dependency.MONGODB, "find");
  private static final String SCRATCH_COLLECTION = "external_call_recorder_it";

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void aNotificationCreateRecordsAMongodbInsert() {
    long before = durationCount(meterRegistry, MONGODB_INSERT);

    createNewNotification("external-call-recorder-it");

    assertThat(durationCount(meterRegistry, MONGODB_INSERT)).isGreaterThan(before);
  }

  @Test
  void aFailedCommandIsRecordedAsAFailureOfItsOperation() {
    double before = failureTotal(meterRegistry, MONGODB_FIND);

    assertThatExceptionOfType(DataAccessException.class)
        .isThrownBy(
            () ->
                mongoTemplate.executeCommand(
                    "{find: '" + SCRATCH_COLLECTION + "', filter: {$notAnOperator: 1}}"));

    assertThat(failureTotal(meterRegistry, MONGODB_FIND)).isEqualTo(before + 1);
  }

  @Test
  void aDuplicateKeyInsertIsRecordedAsAFailureOfInsert() {
    Document document = new Document("_id", new ObjectId());
    mongoTemplate.insert(document, SCRATCH_COLLECTION);
    double before = failureTotal(meterRegistry, MONGODB_INSERT);

    assertThatExceptionOfType(DuplicateKeyException.class)
        .isThrownBy(() -> mongoTemplate.insert(document, SCRATCH_COLLECTION));

    assertThat(failureTotal(meterRegistry, MONGODB_INSERT)).isEqualTo(before + 1);
  }

  @Test
  void theConsoleWritesTheCallLineWithTheTraceIdAndANestedEventObject() throws Exception {
    CapturedConsole console = captureSystemOut();
    try (console) {
      createNewNotification("trace-from-the-caller");
    }

    JsonNode insert = consoleLineWithAction("mongodb/insert", console);

    assertThat(insert.path("trace.id").asText()).isEqualTo("trace-from-the-caller");
    assertThat(insert.path("event").path("category").asText()).isEqualTo("external-call");
    assertThat(insert.path("event").path("outcome").asText()).isEqualTo("success");
    assertThat(insert.path("event").path("duration").isIntegralNumber()).isTrue();
  }

  @Test
  void theConsoleWritesAFailedCallLineWithTheErrorTypeAndMessageButNoStackTrace()
      throws Exception {
    CapturedConsole console = captureSystemOut();
    try (console) {
      assertThatExceptionOfType(DataAccessException.class)
          .isThrownBy(
              () ->
                  mongoTemplate.executeCommand(
                      "{find: '" + SCRATCH_COLLECTION + "', filter: {$notAnOperator: 1}}"));
    }

    JsonNode failedFind = consoleLineWithAction("mongodb/find", console);

    assertThat(failedFind.path("event").path("outcome").asText()).isEqualTo("failure");
    assertThat(failedFind.path("error.type").asText())
        .isEqualTo("com.mongodb.MongoCommandException");
    assertThat(failedFind.path("error.message").asText()).contains("$notAnOperator");
    assertThat(failedFind.has("error.stack_trace")).isFalse();
  }

  private static JsonNode consoleLineWithAction(String action, CapturedConsole console)
      throws Exception {
    for (String line : console.text().split("\n")) {
      if (line.trim().startsWith("{")) {
        JsonNode json = JSON.readTree(line);
        if (action.equals(json.path("event").path("action").asText())) {
          return json;
        }
      }
    }
    throw new AssertionError("No console line has event.action " + action);
  }
}
