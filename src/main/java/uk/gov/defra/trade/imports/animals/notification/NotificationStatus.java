package uk.gov.defra.trade.imports.animals.notification;

public enum NotificationStatus {
    DRAFT, SUBMITTED, AMEND, DELETED,
    /** Submitted while still owed an item, such as the health certificate. */
    SUBMITTED_ACTION_REQUIRED
}
