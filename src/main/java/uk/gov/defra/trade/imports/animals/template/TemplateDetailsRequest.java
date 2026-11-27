package uk.gov.defra.trade.imports.animals.template;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The type and name to record on an existing template")
public record TemplateDetailsRequest(
    @Schema(description = "The journey set the template is made in",
        requiredMode = RequiredMode.REQUIRED, example = "live-animals")
    @NotBlank @Pattern(regexp = StartTemplateRequest.SET_ID_PATTERN)
    String setId,
    @Schema(description = "The template's title, as the user typed it; may be blank",
        requiredMode = RequiredMode.NOT_REQUIRED, example = "Weekly Irish cattle")
    String name) {
}
