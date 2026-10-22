package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationCommoditiesTest {

    @Test
    void entries_shouldReturnCommoditiesListWhenPresent() {
        CommodityComplement cow = CommodityComplement.builder().name("Cow").commodityCode("0102").build();
        Notification notification = Notification.builder()
            .commodities(List.of(cow))
            .build();

        assertThat(NotificationCommodities.entries(notification)).containsExactly(cow);
    }

    @Test
    void entries_shouldExpandLegacyCommodityWithRootNameOnEachComplement() {
        CommodityComplement complement = new CommodityComplement("Domestic", 2, 1, List.of());
        Notification notification = Notification.builder()
            .commodity(Commodity.builder()
                .name("Cow")
                .commodityComplement(List.of(complement))
                .build())
            .build();

        assertThat(NotificationCommodities.entries(notification)).singleElement().satisfies(entry -> {
            assertThat(entry.getName()).isEqualTo("Cow");
            assertThat(entry.getTypeOfCommodity()).isEqualTo("Domestic");
        });
    }
}
