package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class NotificationFulfilmentsSortTest {

    @Test
    void toSort_shouldSupportEveryContractSort() {
        assertSort("arrivalDate,desc", "arrivalDate", Sort.Direction.DESC);
        assertSort("arrivalDate,asc", "arrivalDate", Sort.Direction.ASC);
        assertSort("createdAt,desc", "createdAt", Sort.Direction.DESC);
        assertSort("createdAt,asc", "createdAt", Sort.Direction.ASC);
    }

    @Test
    void toSort_shouldUseArrivalDateDescendingForInvalidOrBlankValues() {
        assertSort(null, "arrivalDate", Sort.Direction.DESC);
        assertSort("", "arrivalDate", Sort.Direction.DESC);
        assertSort("createdAt", "arrivalDate", Sort.Direction.DESC);
        assertSort("createdAt,sideways", "arrivalDate", Sort.Direction.DESC);
        assertSort("submittedAt,asc", "arrivalDate", Sort.Direction.DESC);
        assertSort("status,asc", "arrivalDate", Sort.Direction.DESC);
    }

    private void assertSort(String parameter, String field, Sort.Direction direction) {
        Sort.Order order = NotificationFulfilmentsSort.toSort(parameter).getOrderFor(field);

        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(direction);
    }
}
