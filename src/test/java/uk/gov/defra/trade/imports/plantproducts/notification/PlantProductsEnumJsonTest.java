package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PlantProductsEnumJsonTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumExamples")
    <E extends Enum<E>> void json_shouldRoundTripRepresentativeConstant(
        String name, Class<E> enumClass, E representative) throws Exception {
        // When
        String json = OBJECT_MAPPER.writeValueAsString(representative);
        E result = OBJECT_MAPPER.readValue(json, enumClass);

        // Then
        assertThat(result).isSameAs(representative);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumExamples")
    <E extends Enum<E>> void json_shouldRejectUnknownValue(
        String name, Class<E> enumClass, E representative) {
        // When & Then
        assertThatThrownBy(() -> OBJECT_MAPPER.readValue("\"NOT_A_REAL_VALUE\"", enumClass))
            .isInstanceOf(InvalidFormatException.class);
    }

    static Stream<Arguments> enumExamples() {
        return Stream.of(
            Arguments.of("notification status", PlantProductsNotificationStatus.class,
                PlantProductsNotificationStatus.DRAFT),
            Arguments.of("reason for import", ReasonForImport.class, ReasonForImport.INTERNAL_MARKET),
            Arguments.of("commodity input method", CommodityInputMethod.class, CommodityInputMethod.MANUAL),
            Arguments.of("finished or propagated", FinishedOrPropagated.class, FinishedOrPropagated.FINISHED),
            Arguments.of("variety class", VarietyClass.class, VarietyClass.CLASS_I),
            Arguments.of("gross volume unit", GrossVolumeUnit.class, GrossVolumeUnit.LITRES),
            Arguments.of("means of transport", PlantProductsMeansOfTransport.class,
                PlantProductsMeansOfTransport.VESSEL),
            Arguments.of("common transit convention", CommonTransitConvention.class,
                CommonTransitConvention.ADD_MRN_NOW));
    }
}
