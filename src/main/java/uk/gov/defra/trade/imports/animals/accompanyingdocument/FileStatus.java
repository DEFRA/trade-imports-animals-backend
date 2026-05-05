package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status of an uploaded file as reported by cdp-uploader. cdp-uploader sends lowercase strings
 * ("complete" / "rejected") so each constant carries an explicit {@code @JsonProperty} to ensure
 * round-trip serialisation consistency. The same lowercase strings are stored in MongoDB via the
 * registered {@link FileStatusWriteConverter} / {@link FileStatusReadConverter}.
 */
public enum FileStatus {

  @Schema(description = "File was successfully scanned and is clean")
  @JsonProperty("complete")
  COMPLETE,

  @Schema(description = "File was rejected by antivirus scan")
  @JsonProperty("rejected")
  REJECTED
}
