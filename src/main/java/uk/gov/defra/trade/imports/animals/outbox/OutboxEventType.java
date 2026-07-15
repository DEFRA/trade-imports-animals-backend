package uk.gov.defra.trade.imports.animals.outbox;

/**
 * Names of the outbox event types emitted by the notification lifecycle.
 * The wire value (the string downstream consumers see) is held on the enum;
 * the on-disk schema stores it as a plain String on {@link OutboxEvent}.
 *
 * <p>Each type also carries the {@code schemaUri} of its governing event schema in
 * {@code DEFRA/trade-imports-schemas} — surfaced on {@link OutboxEventMetadata} so
 * downstream consumers can discover and validate against the schema.
 */
public enum OutboxEventType {

    NOTIFICATION_SUBMITTED(
        "uk.gov.defra.imports.notification.NotificationSubmitted",
        "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
            + "schemas/profiles/imports/gb/events/gbn-ag-event-notification-submitted-v1.schema.json"),
    NOTIFICATION_SUBMISSION_AMENDED(
        "uk.gov.defra.imports.notification.NotificationSubmissionAmended",
        "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
            + "schemas/profiles/imports/gb/events/gbn-ag-event-notification-submission-amended-v1.schema.json");

    private final String value;
    private final String schemaUri;

    OutboxEventType(String value, String schemaUri) {
        this.value = value;
        this.schemaUri = schemaUri;
    }

    public String value() {
        return value;
    }

    public String schemaUri() {
        return schemaUri;
    }
}