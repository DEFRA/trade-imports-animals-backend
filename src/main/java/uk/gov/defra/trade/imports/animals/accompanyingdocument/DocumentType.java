package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Classification of an accompanying document submitted with an import notification.
 * Skeleton: ITAHC and Veterinary health certificate only — extend this list as BA confirms
 * additional document types.
 */
public enum DocumentType {

  @Schema(description = "Intra-Trade Animal Health Certificate (ITAHC)")
  ITAHC,

  @Schema(description = "Veterinary health certificate issued by the country of origin")
  VETERINARY_HEALTH_CERTIFICATE
}
