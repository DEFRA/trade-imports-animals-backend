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
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentListResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ClaimUploadRequest;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.animals.notification.NotificationView;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;

class OpenApiDateFormatTest {

    private static final List<Class<?>> API_TYPES = List.of(
        NotificationDto.class,
        SaveNotificationDto.class,
        NotificationAggregate.class,
        NotificationView.class,
        NotificationFulfilmentsView.class,
        ClaimUploadRequest.class,
        AccompanyingDocumentDto.class,
        DocumentListResponse.class);

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
