package uk.gov.defra.trade.imports.animals.outbox;

/**
 * Names of the outbox event types emitted by the notification lifecycle.
 * The wire value (the string downstream consumers see) is held on the enum;
 * the on-disk schema stores it as a plain String on {@link OutboxEvent}.
 */
public enum OutboxEventType {

    NOTIFICATION_SUBMITTED("uk.gov.defra.imports.notification.NotificationSubmitted"),
    NOTIFICATION_SUBMISSION_AMENDED("uk.gov.defra.imports.notification.NotificationSubmissionAmended");

    private final String value;

    OutboxEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}