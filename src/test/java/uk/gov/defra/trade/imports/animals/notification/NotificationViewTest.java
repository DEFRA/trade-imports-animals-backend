package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationViewTest {

    /** Late-evening and sub-second, so a dropped or day-truncated {@code created} cannot pass. */
    private static final Instant CREATED = Instant.parse("2026-09-10T23:35:39.455Z");

    @ParameterizedTest
    @EnumSource(value = NotificationStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void forDashboard_shouldCarryStoredPartiesAsTheyAre(NotificationStatus status) {
        // Given
        ConsignmentParty consignor = ConsignmentParty.builder().name("Stored Consignor").build();
        ConsignmentParty consignee = ConsignmentParty.builder().name("Stored Consignee").build();
        NotificationView view = new NotificationViewData(
            "GBN-AG-26-FRZ001",
            1L,
            status,
            CREATED,
            null,
            null,
            consignor,
            consignee,
            null);

        // When
        NotificationView dashboard = view.forDashboard();

        // Then
        assertThat(dashboard.getStatus()).isEqualTo(status);
        assertThat(dashboard.getCreated()).isEqualTo(CREATED);
        assertThat(dashboard.getConsignor()).isEqualTo(consignor);
        assertThat(dashboard.getConsignee()).isEqualTo(consignee);
    }
}
