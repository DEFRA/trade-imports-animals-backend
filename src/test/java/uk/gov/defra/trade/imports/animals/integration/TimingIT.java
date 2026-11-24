package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TimingIT extends IntegrationBase {

  private static final String CREATE_CONTROLLER_TIMER = "controller.postNotification.time";
  private static final String CREATE_SERVICE_TIMER = "service.saveNotification.time";
  private static final String LIST_CONTROLLER_TIMER = "controller.listDocuments.time";
  private static final String LIST_SERVICE_TIMER = "service.findByNotificationReferenceNumber.time";

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void postNotification_shouldAddOneCallToItsControllerAndServiceTimers() {
    long controllerBefore = timerCount(CREATE_CONTROLLER_TIMER);
    long serviceBefore = timerCount(CREATE_SERVICE_TIMER);

    createNewNotification("timing-it");

    assertThat(timerCount(CREATE_CONTROLLER_TIMER)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(CREATE_SERVICE_TIMER)).isEqualTo(serviceBefore + 1);
  }

  @Test
  void listDocuments_shouldAddOneCallToItsControllerAndServiceTimers() {
    String referenceNumber = createNewNotification("timing-it").getReferenceNumber();
    long controllerBefore = timerCount(LIST_CONTROLLER_TIMER);
    long serviceBefore = timerCount(LIST_SERVICE_TIMER);

    webClient("NoAuth")
        .get()
        .uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}/document-uploads", referenceNumber)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(timerCount(LIST_CONTROLLER_TIMER)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(LIST_SERVICE_TIMER)).isEqualTo(serviceBefore + 1);
  }

  private long timerCount(String name) {
    return meterRegistry.find(name).timers().stream().mapToLong(Timer::count).sum();
  }
}
