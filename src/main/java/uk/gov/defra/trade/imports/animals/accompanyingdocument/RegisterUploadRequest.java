package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for registering an upload session the caller has already opened with cdp-uploader.
 */
@Schema(description = "Request to register a cdp-uploader upload session against a notification")
public record RegisterUploadRequest(
    @NotBlank
    @Size(max = 100)
    @Schema(description = "Identifier cdp-uploader assigned to the upload session",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "9f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8")
    String uploadId,

    @NotBlank
    @Size(max = 100)
    @Schema(description = "Identifier the caller threaded through cdp-uploader's metadata, "
        + "which the scan callback echoes back so the result can be matched to this session",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "1c2d3e4f-5061-7283-94a5-b6c7d8e9f001")
    String correlationId) {}
