package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole.captureSystemOut;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.MONGODB_FIND;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.MONGODB_INSERT;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationSampleCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole;

class ExternalCallRecorderIT extends IntegrationBase {

  private static final String SCRATCH_COLLECTION = "external_call_recorder_it";

  @Autowired private MeterRegistry meterRegistry;

  @AfterEach
  void dropScratchCollection() {
    mongoTemplate.dropCollection(SCRATCH_COLLECTION);
  }

  @Test
  void createNotification_shouldRecordAMongodbInsert() {
    long before = durationSampleCount(meterRegistry, MONGODB_INSERT);

    createNewNotification("external-call-recorder-it");

    assertThat(durationSampleCount(meterRegistry, MONGODB_INSERT)).isGreaterThan(before);
  }

  @Test
  void find_shouldRecordAFailure_whenTheCommandFails() {
    double before = failureTotal(meterRegistry, MONGODB_FIND);

    assertThatExceptionOfType(DataAccessException.class).isThrownBy(this::runFailingFind);

    assertThat(failureTotal(meterRegistry, MONGODB_FIND)).isEqualTo(before + 1);
  }

  @Test
  void insert_shouldRecordASuccess_whenTheKeyIsADuplicate() {
    Document document = new Document("_id", new ObjectId());
    mongoTemplate.insert(document, SCRATCH_COLLECTION);
    long callsBefore = durationSampleCount(meterRegistry, MONGODB_INSERT);
    double failuresBefore = failureTotal(meterRegistry, MONGODB_INSERT);

    assertThatExceptionOfType(DuplicateKeyException.class)
        .isThrownBy(() -> mongoTemplate.insert(document, SCRATCH_COLLECTION));

    assertThat(durationSampleCount(meterRegistry, MONGODB_INSERT)).isEqualTo(callsBefore + 1);
    assertThat(failureTotal(meterRegistry, MONGODB_INSERT)).isEqualTo(failuresBefore);
  }

  @Test
  void createNotification_shouldWriteTheCallLineWithTheTraceIdAndANestedEventObject() {
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
  void find_shouldWriteAFailedCallLineWithNoStackTrace_whenTheCommandFails() {
    CapturedConsole console = captureSystemOut();
    try (console) {
      assertThatExceptionOfType(DataAccessException.class).isThrownBy(this::runFailingFind);
    }

    JsonNode failedFind = consoleLineWithAction("mongodb/find", console);

    assertThat(failedFind.path("event").path("outcome").asText()).isEqualTo("failure");
    assertThat(failedFind.path("error.type").asText())
        .isEqualTo("com.mongodb.MongoCommandException");
    assertThat(failedFind.path("error.message").asText()).contains("$notAnOperator");
    assertThat(failedFind.has("error.stack_trace")).isFalse();
  }

  private void runFailingFind() {
    mongoTemplate.executeCommand(
        "{find: '" + SCRATCH_COLLECTION + "', filter: {$notAnOperator: 1}}");
  }

  private static JsonNode consoleLineWithAction(String action, CapturedConsole console) {
    return console.jsonLines().stream()
        .filter(line -> action.equals(line.path("event").path("action").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No console line has event.action " + action));
  }
}
