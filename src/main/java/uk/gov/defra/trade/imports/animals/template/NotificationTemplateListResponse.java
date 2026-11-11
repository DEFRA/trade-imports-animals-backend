package uk.gov.defra.trade.imports.animals.template;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

@Schema(description = "The signed-in user's saved notification templates, newest first")
public record NotificationTemplateListResponse(
    @Schema(description = "Every template the user has saved, newest first",
        requiredMode = RequiredMode.REQUIRED)
    List<NotificationTemplateSummary> items,
    @Schema(description = "How many templates the user has saved",
        requiredMode = RequiredMode.REQUIRED, example = "2")
    int totalItems) {

    public NotificationTemplateListResponse {
        items = List.copyOf(items);
    }
}
