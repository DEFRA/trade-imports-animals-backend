package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class PlantProductsNotificationSortTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "unknown,asc", "createdAt,sideways", "arrivalDate", "createdAt,asc,extra"})
    void toSort_shouldDefaultToArrivalDateDescending_whenInputIsMissingOrInvalid(String input) {
        // When
        Sort result = PlantProductsNotificationSort.toSort(input);

        // Then
        assertThat(result).containsExactly(Sort.Order.desc("transport.arrivalDate"));
    }

    @ParameterizedTest
    @MethodSource("mappedSorts")
    void toSort_shouldMapSupportedFieldsAndDirections(
        String input, String property, Sort.Direction direction) {
        // When
        Sort.Order order = PlantProductsNotificationSort.toSort(input).getOrderFor(property);

        // Then
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(direction);
    }

    static Stream<Arguments> mappedSorts() {
        return Stream.of(
            Arguments.of("arrivalDate,asc", "transport.arrivalDate", Sort.Direction.ASC),
            Arguments.of("arrivalDate,desc", "transport.arrivalDate", Sort.Direction.DESC),
            Arguments.of("createdAt,asc", "created", Sort.Direction.ASC),
            Arguments.of("createdAt,desc", "created", Sort.Direction.DESC));
    }
}
