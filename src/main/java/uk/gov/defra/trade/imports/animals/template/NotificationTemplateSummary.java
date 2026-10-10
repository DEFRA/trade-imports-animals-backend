package uk.gov.defra.trade.imports.animals.template;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

@Schema(description = "One saved notification template as Manage templates lists it")
public record NotificationTemplateSummary(
    @Schema(description = "The template's identifier", requiredMode = RequiredMode.REQUIRED,
        example = "6650f1c2a1b2c3d4e5f60718")
    String id,
    @Schema(description = "The template's title, as the user typed it",
        requiredMode = RequiredMode.REQUIRED, example = "Arena Aintree")
    String name,
    @Schema(description = "The journey set the template was made in",
        requiredMode = RequiredMode.REQUIRED, example = "live-animals")
    String setId,
    @Schema(description = "When the template was saved", requiredMode = RequiredMode.REQUIRED,
        example = "2026-10-09T10:15:30Z")
    Instant created) {

    static NotificationTemplateSummary from(NotificationTemplate template) {
        return new NotificationTemplateSummary(
            template.getId(), template.getName(), template.getSetId(), template.getCreated());
    }
}
