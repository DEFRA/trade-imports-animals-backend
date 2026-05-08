package uk.gov.defra.trade.imports.animals.accompanyingdocument.file;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status of an uploaded file as reported by cdp-uploader. The enum names are uppercase by Java
 * convention; cdp-uploader's wire format is lowercase ({@code "complete"} / {@code "rejected"}),
 * which is mapped to the matching constants on deserialisation only via {@link JsonAlias}.
 */
public enum FileStatus {

  @JsonAlias("complete")
  @Schema(description = "File was successfully scanned and is clean")
  COMPLETE,

  @JsonAlias("rejected")
  @Schema(description = "File was rejected by antivirus scan")
  REJECTED
}
