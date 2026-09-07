package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationViewTest {

    private static final String ADDRESS_ID = "665f1c2ab3e4d51a2c9d0e77";

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
        NotificationView view = new NotificationView.Data(
            "GBN-AG-26-FRZ001",
            1L,
            NotificationStatus.SUBMITTED,
            null,
            null,
            null,
            consignor,
            consignee,
            null,
            null);

        // When
        NotificationView dashboard = view.forDashboard();

        // Then
        assertThat(dashboard.getConsignor().getName()).isEqualTo("Frozen Consignor");
        assertThat(dashboard.getConsignor().getAddressId()).isNull();
        assertThat(dashboard.getConsignee().getName()).isEqualTo("Frozen Consignee");
        assertThat(dashboard.getConsignee().getAddressId()).isNull();
        assertThat(dashboard.getPreAmendNotification()).isNull();
    }

    @Test
    void forDashboard_shouldKeepLiveReferences_whenDraftOrAmend() {
        // Given
        Notification freeze = Notification.builder()
            .consignor(ConsignmentParty.builder().name("Frozen Consignor").build())
            .build();
        ConsignmentParty liveReference = ConsignmentParty.reference(ADDRESS_ID);
        NotificationView draft = new NotificationView.Data(
            "GBN-AG-26-DRF001",
            0L,
            NotificationStatus.DRAFT,
            null,
            null,
            null,
            liveReference,
            liveReference,
            null,
            freeze);
        NotificationView amend = new NotificationView.Data(
            "GBN-AG-26-AMD001",
            2L,
            NotificationStatus.AMEND,
            null,
            null,
            null,
            liveReference,
            liveReference,
            null,
            freeze);

        // When / Then
        assertThat(draft.forDashboard().getConsignor()).isSameAs(liveReference);
        assertThat(amend.forDashboard().getConsignor()).isSameAs(liveReference);
        assertThat(draft.forDashboard().getPreAmendNotification()).isNull();
    }
}
