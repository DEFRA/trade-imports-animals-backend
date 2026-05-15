package uk.gov.defra.trade.imports.animals.cdp.uploader;

import java.util.Objects;

/**
 * Response received from the cdp-uploader {@code /initiate} endpoint.
 *
 * @param uploadId  unique identifier for the upload session, used to correlate the scan callback
 *                  and to construct the backend file-proxy URL
 * @param statusUrl URL that can be polled to check upload/scan progress; retained for contract
 *                  completeness but not currently consumed by this service
 */
public record CdpUploaderInitiateResponse(String uploadId, String statusUrl) {

  public CdpUploaderInitiateResponse {
    Objects.requireNonNull(uploadId, "uploadId");
  }
}
