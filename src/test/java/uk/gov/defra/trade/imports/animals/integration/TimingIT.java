package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TimingIT extends IntegrationBase {

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void aNotificationCreateAddsOneCallToItsControllerAndServiceTimers() {
    long controllerBefore = timerCount("controller.postNotification.time");
    long serviceBefore = timerCount("service.saveNotification.time");

    createNewNotification("timing-it");

    assertThat(timerCount("controller.postNotification.time")).isEqualTo(controllerBefore + 1);
    assertThat(timerCount("service.saveNotification.time")).isEqualTo(serviceBefore + 1);
  }

  @Test
  void aDocumentListAddsOneCallToItsControllerAndServiceTimers() {
    String referenceNumber = createNewNotification("timing-it").getReferenceNumber();
    long controllerBefore = timerCount("controller.listDocuments.time");
    long serviceBefore = timerCount("service.findByNotificationReferenceNumber.time");

    webClient("NoAuth")
        .get()
        .uri("/notifications/{referenceNumber}/document-uploads", referenceNumber)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(timerCount("controller.listDocuments.time")).isEqualTo(controllerBefore + 1);
    assertThat(timerCount("service.findByNotificationReferenceNumber.time"))
        .isEqualTo(serviceBefore + 1);
  }

  private long timerCount(String name) {
    return meterRegistry.find(name).timers().stream().mapToLong(Timer::count).sum();
  }
}
