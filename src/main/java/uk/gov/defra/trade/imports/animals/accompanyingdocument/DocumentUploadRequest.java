package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
/**
 * Request body for initiating an accompanying document upload.
 */
@Schema(description = "Request to initiate an accompanying document upload")
public record DocumentUploadRequest(
    @NotNull
    @Schema(description = "Type of accompanying document", example = "ITAHC")
    DocumentType documentType,

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[a-zA-Z0-9]*$")
    @Schema(description = "Reference number printed on the document", example = "UKGB2026001234")
    String documentReference,

    @NotNull
    @Schema(description = "Date of issue on the physical document", example = "2026-01-15")
    LocalDate dateOfIssue,

    @Schema(description = "URL to redirect the user to after the upload form is submitted",
        example = "http://localhost:3000/accompanying-documents/upload-received")
    String redirectUrl) {}
