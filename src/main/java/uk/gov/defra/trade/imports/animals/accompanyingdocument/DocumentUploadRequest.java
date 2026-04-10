package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Request body for initiating an accompanying document upload.
 */
@Schema(description = "Request to initiate an accompanying document upload")
public record DocumentUploadRequest(
    @Schema(description = "Type of accompanying document", example = "ITAHC")
    String documentType,

    @Schema(description = "Reference number printed on the document", example = "UK/GB/2026/001234")
    String documentReference,

    @Schema(description = "Date of issue on the physical document", example = "2026-01-15")
    LocalDate dateOfIssue) {}
