package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TimingIT extends IntegrationBase {

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void postNotification_shouldAddOneCallToItsControllerServiceAndAppendEventTimers() {
    String controllerTimer = "controller.postNotification.time";
    String serviceTimer = "service.postNotification.time";
    String appendEventTimer = "service.appendEvent.time";
    long controllerBefore = timerCount(controllerTimer);
    long serviceBefore = timerCount(serviceTimer);
    long appendEventBefore = timerCount(appendEventTimer);

    createNewNotification("timing-it");

    assertThat(timerCount(controllerTimer)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(serviceTimer)).isEqualTo(serviceBefore + 1);
    assertThat(timerCount(appendEventTimer)).isEqualTo(appendEventBefore + 1);
  }

  @Test
  void listDocuments_shouldAddOneCallToItsControllerAndServiceTimers() {
    String controllerTimer = "controller.listDocuments.time";
    String serviceTimer = "service.findByNotificationReferenceNumber.time";
    String referenceNumber = createNewNotification("timing-it").getReferenceNumber();
    long controllerBefore = timerCount(controllerTimer);
    long serviceBefore = timerCount(serviceTimer);

    webClient("NoAuth")
        .get()
        .uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}/document-uploads", referenceNumber)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(timerCount(controllerTimer)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(serviceTimer)).isEqualTo(serviceBefore + 1);
  }

  @Test
  void getOutboxEvents_shouldAddOneCallToItsControllerAndServiceTimers() {
    String controllerTimer = "controller.getOutboxEvents.time";
    String serviceTimer = "service.findByReferenceNumber.time";
    String referenceNumber = createNewNotification("timing-it").getReferenceNumber();
    long controllerBefore = timerCount(controllerTimer);
    long serviceBefore = timerCount(serviceTimer);

    webClient("NoAuth")
        .get()
        .uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}/outbox-events", referenceNumber)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(timerCount(controllerTimer)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(serviceTimer)).isEqualTo(serviceBefore + 1);
  }

  private long timerCount(String name) {
    return meterRegistry.find(name).timers().stream().mapToLong(Timer::count).sum();
  }
}
