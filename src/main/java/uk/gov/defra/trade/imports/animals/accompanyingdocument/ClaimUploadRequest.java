package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Request body for claiming an uploaded document into a notification.
 *
 * @param documentType      the kind of accompanying document; never null
 * @param documentReference the reference printed on the document; never null
 * @param dateOfIssue       the date of issue on the document; never null
 */
@Schema(description = "Request to claim an uploaded document into a notification")
public record ClaimUploadRequest(
    @NotNull
    @Schema(description = "Type of accompanying document",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "ITAHC")
    DocumentType documentType,

    @NotBlank
    @Size(max = ClaimUploadRequest.REFERENCE_MAX_LENGTH)
    @Pattern(regexp = ClaimUploadRequest.REFERENCE_PATTERN)
    @Schema(description = "Reference number printed on the document",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "125-12345675")
    String documentReference,

    @NotNull
    @Schema(description = "Date of issue on the physical document",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "2026-01-15")
    LocalDate dateOfIssue) {

  /**
   * Characters a document reference may carry. Alphanumerics plus the separators real references
   * use: an air waybill is written {@code 125-12345675}, a TRACES certificate
   * {@code CHEDA.GB.2026.1234567}, and a commercial invoice whatever the exporter prints. Length
   * is {@code @Size}'s to enforce, so this rule says nothing about it.
   */
  static final String REFERENCE_PATTERN = "^[A-Za-z0-9 ./-]*$";

  static final int REFERENCE_MAX_LENGTH = 58;
}

