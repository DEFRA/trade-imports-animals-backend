package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.Collections;
import java.util.Map;

/**
 * Top-level payload received from cdp-uploader when a scan completes (the callback request body).
 *
 * @param uploadStatus          overall upload status string (e.g. {@code "ready"})
 * @param metadata              metadata key-value pairs originally passed at initiation time;
 *                              wrapped in an unmodifiable view after deserialization
 * @param form                  form section containing the scanned file entries
 * @param numberOfRejectedFiles count of files rejected by antivirus scanning; a {@code null} value
 *                              should be treated as if all files were rejected
 */
public record CdpScanResultPayload(
    String uploadStatus,
    Map<String, String> metadata,
    CdpScanResultForm form,
    Integer numberOfRejectedFiles) {

  public CdpScanResultPayload {
    metadata = Collections.unmodifiableMap(metadata != null ? metadata : Map.of());
  }
}
