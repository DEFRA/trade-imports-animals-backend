package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxEventTypeTest {

    // These expected literals are hardcoded on purpose. Consuming tests assert against
    // OutboxEventType.X.schemaUrl(), which is self-referential and would not catch a
    // typo'd or swapped-between-constants URL. This test pins each constant's schemaUrl()
    // to its exact governing schema URL so such a regression fails here.

    @Test
    void notificationSubmitted_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-submitted-v1.schema.json");
    }

    @Test
    void notificationSubmissionAmended_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-submission-amended-v1.schema.json");
    }

    @Test
    void notificationEdited_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_EDITED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-edited-v1.schema.json");
    }

    @Test
    void notificationCreated_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_CREATED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-created-v1.schema.json");
    }

    @Test
    void notificationAmendmentRequested_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_AMENDMENT_REQUESTED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-amendment-requested-v1.schema.json");
    }

    @Test
    void notificationAmendmentCancelled_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_AMENDMENT_CANCELLED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-amendment-cancelled-v1.schema.json");
    }

    @Test
    void notificationDeleted_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_DELETED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-deleted-v1.schema.json");
    }

    @Test
    void notificationSubmissionDeleted_schemaUrl_matchesGoverningSchema() {
        assertThat(OutboxEventType.NOTIFICATION_SUBMISSION_DELETED.schemaUrl())
            .isEqualTo(
                "https://github.com/DEFRA/trade-imports-schemas/blob/main/"
                    + "schemas/profiles/imports/gb/events/"
                    + "gbn-ag-event-notification-submission-deleted-v1.schema.json");
    }

    @Test
    void allEventTypes_haveCorrectNamespacePrefix() {
        String expectedPrefix = "uk.gov.defra.imports.notification.";
        for (OutboxEventType type : OutboxEventType.values()) {
            assertThat(type.value())
                .as("wire value for %s", type)
                .startsWith(expectedPrefix);
        }
    }
}
