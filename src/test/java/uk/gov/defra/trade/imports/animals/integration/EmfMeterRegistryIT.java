package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole.captureSystemOut;
import static uk.gov.defra.trade.imports.animals.testsupport.EmfDocuments.isDocumentFor;
import static uk.gov.defra.trade.imports.animals.testsupport.EmfDocuments.namespaceOf;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.metrics.EmfMeterRegistry;
import uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole;

@DirtiesContext
@TestPropertySource(
    properties = {
      "aws.emf.enabled=true",
      "aws.emf.environment=Local",
      "aws.emf.namespace=trade-imports-animals-backend",
      "aws.emf.step=1s"
    })
class EmfMeterRegistryIT extends IntegrationBase {

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void meterRegistry_shouldBeTheEmfMeterRegistry_whenEmfIsEnabled() {
    assertThat(meterRegistry).isInstanceOf(EmfMeterRegistry.class);
  }

  @Test
  void allowlist_shouldLetThroughTheMetersANotificationCreateRecords() {
    createNewNotification("emf-registry-it");

    for (String name :
        List.of(
            "external.call.duration",
            "external.call.failure",
            "controller.postNotification.time",
            "service.postNotification.time")) {
      assertThat(meterRegistry.find(name).meter()).as(name).isNotNull();
    }
  }

  @Test
  void allowlist_shouldLetThroughTheDependencyAvailabilityGauge() {
    assertThat(meterRegistry.find("external.dependency.available").gauge()).isNotNull();
  }

  @Test
  void emfMeterRegistry_shouldPublishARecordedCall_whenItsStepElapses() {
    CapturedConsole console = captureSystemOut();
    try (console) {
      createNewNotification("emf-registry-it");

      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofSeconds(1))
          .until(() -> emfDocumentFor("external.call.duration", console).isPresent());
    }

    JsonNode document = emfDocumentFor("external.call.duration", console).orElseThrow();
    assertThat(namespaceOf(document)).isEqualTo("trade-imports-animals-backend");
    assertThat(document.path("dependency").asText()).isEqualTo("mongodb");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cache.gets",
        "mongodb.driver.commands",
        "spring.data.repository.invocations",
        "http.server.requests.active"
      })
  void allowlist_shouldNotRegisterAMeter_whenItsNameIsNotListed(String name) {
    meterRegistry.counter(name).increment();

    assertThat(meterRegistry.find(name).meter()).isNull();
  }

  private static Optional<JsonNode> emfDocumentFor(String metricName, CapturedConsole console) {
    return console.jsonLines().stream()
        .filter(line -> isDocumentFor(line, metricName))
        .findFirst();
  }
}
