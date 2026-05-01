package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response returned after successfully initiating an accompanying document upload session.
 */
@Schema(description = "Upload session details returned after initiation")
public record DocumentUploadResponse(
    @Schema(description = "Unique identifier for the upload session assigned by cdp-uploader", requiredMode = Schema.RequiredMode.REQUIRED)
    String uploadId,

    @Schema(description = "URL of the cdp-uploader form to which the user should be redirected", requiredMode = Schema.RequiredMode.REQUIRED)
    String uploadUrl) {}
