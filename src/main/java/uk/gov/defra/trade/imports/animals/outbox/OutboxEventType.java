package uk.gov.defra.trade.imports.animals.outbox;

import java.util.EnumSet;
import java.util.Set;

/**
 * Names of the outbox event types emitted by the notification lifecycle.
 * The wire value (the string downstream consumers see) is held on the enum;
 * the on-disk schema stores it as a plain String on {@link OutboxEvent}.
 *
 * <p>Each type also carries the {@code schemaUrl} of its governing event schema in
 * {@code DEFRA/trade-imports-schemas} — surfaced on {@link OutboxEventMetadata} so
 * downstream consumers can discover and validate against the schema.
 */
public enum OutboxEventType {
    
    NOTIFICATION_SUBMITTED(
        "NotificationSubmitted",
        "gbn-ag-event-notification-submitted-v1.schema.json"),
    NOTIFICATION_SUBMISSION_AMENDED(
        "NotificationSubmissionAmended",
        "gbn-ag-event-notification-submission-amended-v1.schema.json"),
    NOTIFICATION_EDITED(
        "NotificationEdited",
        "gbn-ag-event-notification-edited-v1.schema.json"),
    NOTIFICATION_CREATED(
        "NotificationCreated",
        "gbn-ag-event-notification-created-v1.schema.json"),
    NOTIFICATION_AMENDMENT_REQUESTED(
        "NotificationAmendmentRequested",
        "gbn-ag-event-notification-amendment-requested-v1.schema.json"),
    NOTIFICATION_AMENDMENT_CANCELLED(
        "NotificationAmendmentCancelled",
        "gbn-ag-event-notification-amendment-cancelled-v1.schema.json"),
    NOTIFICATION_DELETED(
        "NotificationDeleted",
        "gbn-ag-event-notification-deleted-v1.schema.json"),
    NOTIFICATION_SUBMISSION_DELETED(
        "NotificationSubmissionDeleted",
        "gbn-ag-event-notification-submission-deleted-v1.schema.json");

    /** Event types that represent a submission and therefore increment versionId. */
    public static final Set<OutboxEventType> SUBMISSION_EVENTS = EnumSet.of(
        NOTIFICATION_SUBMITTED,
        NOTIFICATION_SUBMISSION_AMENDED);

    private final String value;
    private final String schemaUrl;

    OutboxEventType(String value, String schemaUrl) {
        this.value = "uk.gov.defra.imports.notification." + value;
        this.schemaUrl = "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
            + "schemas/profiles/imports/gb/events/" + schemaUrl;
    }

    public String value() {
        return value;
    }

    public String schemaUrl() {
        return schemaUrl;
    }
}