package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status of an uploaded file as reported by cdp-uploader. cdp-uploader sends lowercase strings
 * ("complete" / "rejected") and the same lowercase strings are stored in MongoDB.
 *
 * <p>The lowercase mapping is owned here on the enum so that JSON (via {@link JsonValue} /
 * {@link JsonCreator}) and MongoDB (via {@link FileStatusReadConverter} /
 * {@link FileStatusWriteConverter}) share a single source of truth.
 */
public enum FileStatus {

  @Schema(description = "File was successfully scanned and is clean")
  COMPLETE("complete"),

  @Schema(description = "File was rejected by antivirus scan")
  REJECTED("rejected");

  private final String storageValue;

  FileStatus(String storageValue) {
    this.storageValue = storageValue;
  }

  @JsonValue
  public String storageValue() {
    return storageValue;
  }

  @JsonCreator
  public static FileStatus fromStorageValue(String value) {
    for (FileStatus status : values()) {
      if (status.storageValue.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unrecognised FileStatus: " + value);
  }
}
