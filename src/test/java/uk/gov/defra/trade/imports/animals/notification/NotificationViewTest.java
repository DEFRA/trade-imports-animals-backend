package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationViewTest {

    private static final String ADDRESS_ID = "665f1c2ab3e4d51a2c9d0e77";

    /** Late-evening and sub-second, so a dropped or day-truncated {@code created} cannot pass. */
    private static final Instant CREATED = Instant.parse("2026-09-10T23:35:39.455Z");

    @Test
    void forDashboard_shouldInlineStoredPartiesWithoutAddressId_whenSubmitted() {
        // Given — inline details on the notification fields (frozen at submit)
        ConsignmentParty consignor = ConsignmentParty.builder()
            .addressId(ADDRESS_ID)
            .name("Frozen Consignor")
            .build();
        ConsignmentParty consignee = ConsignmentParty.builder()
            .addressId(ADDRESS_ID)
            .name("Frozen Consignee")
            .build();
        NotificationView view = new NotificationViewData(
            "GBN-AG-26-FRZ001",
            1L,
            NotificationStatus.SUBMITTED,
            CREATED,
            null,
            null,
            consignor,
            consignee,
            null);

        // When
        NotificationView dashboard = view.forDashboard();

        // Then
        assertThat(dashboard.getCreated()).isEqualTo(CREATED);
        assertThat(dashboard.getConsignor().getName()).isEqualTo("Frozen Consignor");
        assertThat(dashboard.getConsignor().getAddressId()).isNull();
        assertThat(dashboard.getConsignee().getName()).isEqualTo("Frozen Consignee");
        assertThat(dashboard.getConsignee().getAddressId()).isNull();
    }

    @Test
    void forDashboard_shouldKeepLiveReferences_whenDraftOrAmend() {
        // Given
        ConsignmentParty liveReference = ConsignmentParty.reference(ADDRESS_ID);
        NotificationView draft = new NotificationViewData(
            "GBN-AG-26-DRF001",
            0L,
            NotificationStatus.DRAFT,
            null,
            null,
            null,
            liveReference,
            liveReference,
            null);
        NotificationView amend = new NotificationViewData(
            "GBN-AG-26-AMD001",
            2L,
            NotificationStatus.AMEND,
            null,
            null,
            null,
            liveReference,
            liveReference,
            null);

        // When / Then
        assertThat(draft.forDashboard().getConsignor()).isSameAs(liveReference);
        assertThat(amend.forDashboard().getConsignor()).isSameAs(liveReference);
    }
}
