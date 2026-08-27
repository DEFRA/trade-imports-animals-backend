package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class NotificationAggregateTest {

    @Test
    void requireNotification_shouldReturnTheNotification_whenPresent() {
        Notification notification = Notification.builder().build();
        NotificationAggregate aggregate = NotificationAggregate.builder()
            .notification(notification)
            .build();

        assertThat(aggregate.requireNotification()).isSameAs(notification);
    }

    @Test
    void requireNotification_shouldThrow_whenNotificationAbsent() {
        NotificationAggregate aggregate = new NotificationAggregate();

        assertThatNullPointerException()
            .isThrownBy(aggregate::requireNotification)
            .withMessageContaining("requires a notification sub-object");
    }
}
