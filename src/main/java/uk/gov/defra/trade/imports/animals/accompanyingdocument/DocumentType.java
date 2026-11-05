package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;

/** Classification of an accompanying document submitted with an import notification. */
public enum DocumentType {
  @Schema(description = "ITAHC (Intra Trade Animal Health Certificate)")
  ITAHC,

  @Schema(description = "Other veterinary certificate or veterinary declaration")
  VETERINARY_HEALTH_CERTIFICATE,

  @Schema(description = "Self-declaration")
  SELF_DECLARATION,

  @Schema(description = "Journey log")
  JOURNEY_LOG,

  @Schema(description = "Import permit or authorisation")
  IMPORT_PERMIT,

  @Schema(description = "CITES permit or certificate")
  CITES_PERMIT_OR_CERTIFICATE,

  @Schema(description = "Air waybill")
  AIR_WAYBILL,

  @Schema(description = "Sea waybill")
  SEA_WAYBILL,

  @Schema(description = "Rail waybill")
  RAIL_WAYBILL,

  @Schema(description = "Bill of lading")
  BILL_OF_LADING,

  @Schema(description = "Commercial invoice")
  COMMERCIAL_INVOICE,

  @Schema(description = "Packing list or species list")
  PACKING_OR_SPECIES_LIST,

  @Schema(description = "Other supporting documents")
  OTHER
}
