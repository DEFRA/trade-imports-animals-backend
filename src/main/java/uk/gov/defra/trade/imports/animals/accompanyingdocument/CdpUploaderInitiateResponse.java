package uk.gov.defra.trade.imports.animals.accompanyingdocument;

/**
 * Response received from the cdp-uploader {@code /initiate} endpoint.
 *
 * @param uploadId  unique identifier for the upload session, used to correlate the scan callback
 * @param uploadUrl URL to which the client should POST the file multipart form data
 * @param statusUrl URL that can be polled to check upload/scan progress; retained for contract
 *                  completeness but not currently consumed by this service
 */
public record CdpUploaderInitiateResponse(String uploadId, String uploadUrl, String statusUrl) {}
