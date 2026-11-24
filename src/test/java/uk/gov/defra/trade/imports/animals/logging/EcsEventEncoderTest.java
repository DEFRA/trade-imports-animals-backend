package uk.gov.defra.trade.imports.animals.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class EcsEventEncoderTest {

  private static final Logger LOG = LoggerFactory.getLogger(EcsEventEncoderTest.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final EcsEventEncoder encoder = new EcsEventEncoder();
  private final EcsEncoder ecsEncoder = new EcsEncoder();

  @BeforeEach
  void startEncoders() {
    var context = (LoggerContext) LoggerFactory.getILoggerFactory();
    for (EcsEncoder each : new EcsEncoder[] {encoder, ecsEncoder}) {
      each.setContext(context);
      each.setServiceName("trade-imports-animals-backend");
      each.start();
    }
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void encode_nestsTheEventKeyValuePairs_withAnIntegralDuration() throws IOException {
    ILoggingEvent event =
        logged(
            () ->
                LOG.atInfo()
                    .addKeyValue("event.category", "external-call")
                    .addKeyValue("event.action", "mongodb/find")
                    .addKeyValue("event.outcome", "success")
                    .addKeyValue("event.duration", 2_882_625L)
                    .log("External call mongodb/find success in 2 ms"));

    JsonNode line = encode(event);

    JsonNode eventObject = line.path("event");
    assertThat(eventObject.isObject()).isTrue();
    assertThat(eventObject.path("category").asText()).isEqualTo("external-call");
    assertThat(eventObject.path("action").asText()).isEqualTo("mongodb/find");
    assertThat(eventObject.path("outcome").asText()).isEqualTo("success");
    assertThat(eventObject.path("duration").isIntegralNumber()).isTrue();
    assertThat(eventObject.path("duration").asLong()).isEqualTo(2_882_625L);
  }

  @Test
  void encode_shouldKeepTheThrowablesErrorFields_withTheEventObject() throws IOException {
    ILoggingEvent event =
        logged(
            () ->
                LOG.atWarn()
                    .addKeyValue("event.outcome", "failure")
                    .setCause(new IllegalStateException("mongo unavailable"))
                    .log("External call failed"));

    JsonNode line = encode(event);

    assertThat(line.path("error.type").asText()).isEqualTo("java.lang.IllegalStateException");
    assertThat(line.path("error.message").asText()).isEqualTo("mongo unavailable");
    assertThat(line.has("error.stack_trace")).isTrue();
    assertThat(line.path("event").path("outcome").asText()).isEqualTo("failure");
  }

  @Test
  void encode_writesOnlyTheErrorKeyValuePairsAsTopLevelFields() throws IOException {
    ILoggingEvent event =
        logged(
            () ->
                LOG.atWarn()
                    .addKeyValue("event.outcome", "failure")
                    .addKeyValue("error.type", "java.lang.IllegalStateException")
                    .addKeyValue("error.message", "mongo unavailable")
                    .log("External call failed"));

    JsonNode line = encode(event);

    assertThat(line.path("error.type").asText()).isEqualTo("java.lang.IllegalStateException");
    assertThat(line.path("error.message").asText()).isEqualTo("mongo unavailable");
    assertThat(line.has("event.outcome")).isFalse();
    assertThat(line.path("event").path("outcome").asText()).isEqualTo("failure");
  }

  @Test
  void encode_writesTheThrowablesErrorFieldsInsteadOfTheErrorKeyValuePairs() {
    ILoggingEvent event =
        logged(
            () ->
                LOG.atWarn()
                    .addKeyValue("error.type", "java.lang.IllegalArgumentException")
                    .addKeyValue("error.message", "from the key-value pair")
                    .setCause(new IllegalStateException("from the throwable"))
                    .log("External call failed"));

    String line = new String(encoder.encode(event));

    assertThat(line)
        .contains("\"error.type\":\"java.lang.IllegalStateException\"")
        .contains("\"error.message\":\"from the throwable\"")
        .doesNotContain("java.lang.IllegalArgumentException")
        .doesNotContain("from the key-value pair");
  }

  @Test
  void encode_writesALineWithNoKeyValuePairsExactlyAsTheEcsEncoderDoes() {
    MDC.put("trace.id", "trace-from-the-caller");
    ILoggingEvent event = logged(() -> LOG.info("Started"));

    assertThat(new String(encoder.encode(event), StandardCharsets.UTF_8))
        .isEqualTo(new String(ecsEncoder.encode(event), StandardCharsets.UTF_8));
  }

  @Test
  void encode_writesALineWithNoEventKeyValuePairsExactlyAsTheEcsEncoderDoes() {
    MDC.put("trace.id", "trace-from-the-caller");
    ILoggingEvent event =
        logged(() -> LOG.atInfo().addKeyValue("other.key", "value").log("Started"));

    assertThat(new String(encoder.encode(event))).isEqualTo(new String(ecsEncoder.encode(event)));
  }

  @Test
  void encode_escapesStringValues() throws IOException {
    ILoggingEvent event =
        logged(() -> LOG.atInfo().addKeyValue("event.action", "say \"hi\" \\ there").log("Hi"));

    JsonNode line = encode(event);

    assertThat(line.path("event").path("action").asText()).isEqualTo("say \"hi\" \\ there");
  }

  @Test
  void encode_writesANullValueAsJsonNull() throws IOException {
    ILoggingEvent event =
        logged(() -> LOG.atInfo().addKeyValue("event.outcome", (Object) null).log("No outcome"));

    JsonNode outcome = encode(event).path("event").path("outcome");

    assertThat(outcome.isNull()).isTrue();
  }

  @Test
  void encode_writesANonFiniteNumberAsAString() throws IOException {
    ILoggingEvent event =
        logged(
            () ->
                LOG.atInfo()
                    .addKeyValue("event.duration", Double.NaN)
                    .addKeyValue("event.risk_score", Float.POSITIVE_INFINITY)
                    .log("Not a number"));

    JsonNode line = encode(event);

    assertThat(line.path("event").path("duration").asText()).isEqualTo("NaN");
    assertThat(line.path("event").path("risk_score").asText()).isEqualTo("Infinity");
  }

  private static ILoggingEvent logged(Runnable logging) {
    return loggedBy(EcsEventEncoderTest.class, logging).getFirst();
  }

  private JsonNode encode(ILoggingEvent event) throws IOException {
    return JSON.readTree(encoder.encode(event));
  }
}
