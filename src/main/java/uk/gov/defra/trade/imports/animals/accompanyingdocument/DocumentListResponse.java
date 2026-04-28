package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/**
 * Wrapper response for a list of accompanying document DTOs.
 */
@Schema(description = "List of accompanying documents for a notification")
public record DocumentListResponse(
    @Schema(description = "Accompanying document entries", requiredMode = Schema.RequiredMode.REQUIRED)
    List<AccompanyingDocumentDto> items) {

  public DocumentListResponse {
    Objects.requireNonNull(items, "items");
  }
}
