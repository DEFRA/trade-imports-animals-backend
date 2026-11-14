package uk.gov.defra.trade.imports.animals.notification;

public enum NotificationStatus {
    DRAFT, SUBMITTED, SUBMITTED_ACTION_REQUIRED, AMEND, DELETED;

    public boolean isSubmitted() {
        return this == SUBMITTED || this == SUBMITTED_ACTION_REQUIRED;
    }
}
