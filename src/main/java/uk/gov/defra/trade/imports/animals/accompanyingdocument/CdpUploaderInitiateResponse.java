package uk.gov.defra.trade.imports.animals.accompanyingdocument;

/**
 * Response received from the cdp-uploader {@code /initiate} endpoint.
 */
public record CdpUploaderInitiateResponse(String uploadId, String uploadUrl, String statusUrl) {}
