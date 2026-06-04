package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class NotificationSortTest {

    @Test
    void toSort_shouldDefaultToArrivalDateDescending_whenMissingOrInvalid() {
        assertThat(NotificationSort.toSort(null).getOrderFor("transport.arrivalDate").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(NotificationSort.toSort("").getOrderFor("transport.arrivalDate").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(NotificationSort.toSort("invalid").getOrderFor("transport.arrivalDate").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void toSort_shouldMapArrivalDateSort() {
        assertThat(NotificationSort.toSort("arrivalDate,asc").getOrderFor("transport.arrivalDate").getDirection())
            .isEqualTo(Sort.Direction.ASC);
        assertThat(NotificationSort.toSort("arrivalDate,desc").getOrderFor("transport.arrivalDate").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void toSort_shouldMapCreatedAtSort() {
        assertThat(NotificationSort.toSort("createdAt,asc").getOrderFor("created").getDirection())
            .isEqualTo(Sort.Direction.ASC);
        assertThat(NotificationSort.toSort("createdAt,desc").getOrderFor("created").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }
}
