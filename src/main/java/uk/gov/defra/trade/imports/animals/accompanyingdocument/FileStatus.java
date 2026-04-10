package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status of an uploaded file as reported by cdp-uploader. cdp-uploader sends lowercase strings
 * ("complete" / "rejected") so each constant carries an explicit {@code @JsonProperty} to ensure
 * round-trip serialisation consistency. The same lowercase strings are stored in MongoDB via the
 * registered {@link FileStatusWriteConverter} / {@link FileStatusReadConverter}.
 */
public enum FileStatus {

  @JsonProperty("complete")
  COMPLETE,

  @JsonProperty("rejected")
  REJECTED
}
