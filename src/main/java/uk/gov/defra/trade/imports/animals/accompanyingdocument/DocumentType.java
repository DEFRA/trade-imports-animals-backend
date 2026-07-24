package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Classification of an accompanying document submitted with an import notification.
 *
 * <p>The complete V4 set is aligned with the frontend accompanying-document options.
 */
public enum DocumentType {
  @Schema(description = "Intra-Trade Animal Health Certificate (ITAHC)")
  ITAHC,

  @Schema(description = "Veterinary health certificate issued by the country of origin")
  VETERINARY_HEALTH_CERTIFICATE,

  @Schema(description = "Air waybill")
  AIR_WAYBILL,

  @Schema(description = "Import permit")
  IMPORT_PERMIT,

  @Schema(description = "Letter of authority")
  LETTER_OF_AUTHORITY,

  @Schema(description = "Commercial invoice")
  COMMERCIAL_INVOICE,

  @Schema(description = "Sea waybill")
  SEA_WAYBILL,

  @Schema(description = "Rail waybill")
  RAIL_WAYBILL,

  @Schema(description = "Bill of lading")
  BILL_OF_LADING,

  @Schema(description = "Catch certificate")
  CATCH_CERTIFICATE,

  @Schema(description = "Laboratory sampling results for aflatoxin")
  LABORATORY_SAMPLING_RESULTS_FOR_AFLATOXIN,

  @Schema(description = "Health certificate")
  HEALTH_CERTIFICATE,

  @Schema(description = "Journey log")
  JOURNEY_LOG,

  @Schema(description = "Other accompanying document")
  OTHER
}
