package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;

/** Virus/malware scan status for an accompanying document upload. */
public enum ScanStatus {

  @Schema(description = "File has been uploaded and is awaiting antivirus scanning")
  PENDING,

  @Schema(description = "Antivirus scan completed successfully — file is clean")
  COMPLETE,

  @Schema(description = "Antivirus scan rejected the file (virus or malware detected)")
  REJECTED
}
