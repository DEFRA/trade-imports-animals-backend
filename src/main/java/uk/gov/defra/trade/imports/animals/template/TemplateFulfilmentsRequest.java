package uk.gov.defra.trade.imports.animals.template;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.bson.Document;

@Schema(description = "The answers to hold on a template, replacing any held before")
public record TemplateFulfilmentsRequest(
    @Schema(description = "The notification answers, persisted byte-faithfully",
        requiredMode = RequiredMode.REQUIRED)
    @NotNull List<Document> fulfilments) {

    public TemplateFulfilmentsRequest {
        fulfilments = fulfilments == null ? null : List.copyOf(fulfilments);
    }
}
