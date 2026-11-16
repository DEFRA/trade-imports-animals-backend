package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.logEventsDuring;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.messagesOf;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;

class NotificationExpiryStampingLogTest {

    private static List<String> messagesLoggedBy(NotificationTtlConfig ttlConfig) {
        return messagesOf(logEventsDuring(NotificationExpiryStampingLog.class,
            new NotificationExpiryStampingLog(ttlConfig)::logExpiryStamping));
    }

    @Test
    void logExpiryStamping_shouldGiveDaysAndEnvironment_whenStampingIsOn() {
        assertThat(messagesLoggedBy(new NotificationTtlConfig(7, "dev")))
            .containsExactly("Notification expiry stamping is on: 7 days in environment dev");
    }

    @Test
    void logExpiryStamping_shouldNameProdAsTheReason_whenDaysAreSetInProd() {
        assertThat(messagesLoggedBy(new NotificationTtlConfig(7, "prod")))
            .containsExactly("Notification expiry stamping is off: environment prod is prod");
    }

    @Test
    void logExpiryStamping_shouldNameMissingDaysAsTheReason_whenNoDaysAreSetOutsideProd() {
        assertThat(messagesLoggedBy(new NotificationTtlConfig(null, "dev")))
            .containsExactly("Notification expiry stamping is off: no days set in environment dev");
    }

    @Test
    void logExpiryStamping_shouldListenForTheApplicationBeingReady() throws NoSuchMethodException {
        EventListener listener = NotificationExpiryStampingLog.class
            .getMethod("logExpiryStamping")
            .getAnnotation(EventListener.class);

        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
