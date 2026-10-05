package uk.gov.defra.trade.imports.animals;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentUploadRequest;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.animals.notification.NotificationView;

/**
 * The OpenAPI schema tells a caller which form each date takes: {@code format: date} for a
 * calendar date and {@code format: date-time} for a moment. Both are derived from the Java type,
 * so this fails if a date-only field is typed as a moment again, or the reverse.
 *
 * <p>The schemas are resolved with the swagger model converters springdoc itself uses, rather
 * than read from {@code /v3/api-docs}. Every schema reachable from the request and response
 * types is searched by property name, so each one that carries the field is held to the same
 * format.
 */
class OpenApiDateFormatTest {

    private static final List<Class<?>> API_TYPES = List.of(
        NotificationDto.class,
        NotificationView.class,
        NotificationFulfilmentsView.class,
        DocumentUploadRequest.class,
        AccompanyingDocumentDto.class);

    @ParameterizedTest
    @CsvSource({
        "arrivalDate, date",
        "exitDate, date",
        "dateOfIssue, date",
        "created, date-time",
        "updated, date-time",
        "submittedAt, date-time"
    })
    void schema_shouldDescribeEachDateFieldWithTheFormatOfItsType(
        String property, String expectedFormat) {
        List<String> formats = new ArrayList<>();
        for (Class<?> type : API_TYPES) {
            for (Schema<?> schema : ModelConverters.getInstance().readAll(type).values()) {
                Map<String, Schema> properties = schema.getProperties();
                if (properties != null && properties.containsKey(property)) {
                    formats.add(properties.get(property).getFormat());
                }
            }
        }

        assertThat(formats)
            .as("format of every '%s' property in the API schemas", property)
            .isNotEmpty()
            .containsOnly(expectedFormat);
    }
}
