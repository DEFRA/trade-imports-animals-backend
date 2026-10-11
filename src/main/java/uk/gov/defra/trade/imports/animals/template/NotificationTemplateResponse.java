package uk.gov.defra.trade.imports.animals.template;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.bson.Document;

@Schema(description = "One template in full, including the answers it holds")
public record NotificationTemplateResponse(
    @Schema(description = "The template's identifier", requiredMode = RequiredMode.REQUIRED,
        example = "6650f1c2a1b2c3d4e5f60718")
    String id,
    @Schema(description = "The template's title, as the user typed it",
        requiredMode = RequiredMode.NOT_REQUIRED, example = "Weekly Irish cattle")
    String name,
    @Schema(description = "The journey set the template was made in",
        requiredMode = RequiredMode.REQUIRED, example = "live-animals")
    String setId,
    @Schema(description = "Whether the template is still being created or saved",
        requiredMode = RequiredMode.REQUIRED, example = "IN_PROGRESS")
    TemplateStatus status,
    @Schema(description = "Optimistic locking version", requiredMode = RequiredMode.NOT_REQUIRED,
        example = "0")
    Long version,
    @Schema(description = "When the template was created", requiredMode = RequiredMode.NOT_REQUIRED,
        example = "2026-10-09T10:15:30Z")
    Instant created,
    @Schema(description = "When the template was last changed",
        requiredMode = RequiredMode.NOT_REQUIRED, example = "2026-10-09T10:15:30Z")
    Instant updated,
    @Schema(description = "The notification answers the template holds",
        requiredMode = RequiredMode.REQUIRED)
    List<Document> fulfilments) {

    static NotificationTemplateResponse from(NotificationTemplate template) {
        TemplateStatus status = template.getStatus() == null
            ? TemplateStatus.SAVED : template.getStatus();
        List<Document> fulfilments = template.getFulfilments() == null
            ? List.of() : List.copyOf(template.getFulfilments());
        return new NotificationTemplateResponse(
            template.getId(), template.getName(), template.getSetId(), status,
            template.getVersion(), template.getCreated(), template.getUpdated(), fulfilments);
    }
}
