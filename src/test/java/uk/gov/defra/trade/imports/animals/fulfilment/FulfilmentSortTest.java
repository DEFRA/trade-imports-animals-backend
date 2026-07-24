package uk.gov.defra.trade.imports.animals.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class FulfilmentSortTest {

    @Test
    void toSort_shouldSupportEveryContractSort() {
        assertSort("createdAt,desc", "createdAt", Sort.Direction.DESC);
        assertSort("createdAt,asc", "createdAt", Sort.Direction.ASC);
        assertSort("submittedAt,desc", "submittedAt", Sort.Direction.DESC);
        assertSort("submittedAt,asc", "submittedAt", Sort.Direction.ASC);
    }

    @Test
    void toSort_shouldUseCreatedAtDescendingForInvalidOrBlankValues() {
        assertSort(null, "createdAt", Sort.Direction.DESC);
        assertSort("", "createdAt", Sort.Direction.DESC);
        assertSort("createdAt", "createdAt", Sort.Direction.DESC);
        assertSort("createdAt,sideways", "createdAt", Sort.Direction.DESC);
        assertSort("status,asc", "createdAt", Sort.Direction.DESC);
    }

    private void assertSort(String parameter, String field, Sort.Direction direction) {
        Sort.Order order = FulfilmentSort.toSort(parameter).getOrderFor(field);

        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(direction);
    }
}
