package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;

/**
 * Classification of an accompanying document submitted with an import notification.
 * Skeleton: ITAHC and Veterinary health certificate only — extend this list as BA confirms
 * additional document types.
 */
public enum DocumentType {
  @Schema(description = "Intra-Trade Animal Health Certificate (ITAHC)")
  ITAHC,

  @Schema(description = "Veterinary health certificate issued by the country of origin")
  VETERINARY_HEALTH_CERTIFICATE;

  /**
   * Tolerant parse of a raw string into a {@link DocumentType}. Intended for external inputs
   * (e.g. cdp-uploader scan-result callback form fields) where invalid values should be dropped
   * rather than surfaced as an exception that would fail the containing payload.
   *
   * <p>Returns {@link Optional#empty()} for {@code null}, blank strings, and any value that does
   * not match a declared enum constant (case-sensitive). Otherwise returns the matched constant
   * wrapped in an {@link Optional}.
   *
   * @param raw the untrusted input; may be {@code null}
   * @return the parsed {@code DocumentType}, or empty if input was null/blank/unknown
   */
  public static Optional<DocumentType> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(DocumentType.valueOf(raw));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
