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
}
