package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFileDto;

/**
 * Read-only projection of an {@link AccompanyingDocument} for API responses.
 *
 * <p>Use {@link #from(AccompanyingDocument)} to create an instance from the entity.
 */
public record AccompanyingDocumentDto(
    String id,
    String notificationReferenceNumber,
    String uploadId,

    @Schema(description = "The handle the upload session was opened under",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String correlationId,
    DocumentType documentType,
    String documentReference,
    LocalDate dateOfIssue,
    ScanStatus scanStatus,

    @Schema(description = "Whether the trader's document details have been supplied; an upload the "
        + "user walked away from stays unclaimed",
        requiredMode = Schema.RequiredMode.REQUIRED)
    boolean claimed,
    List<UploadedFileDto> files,
    Instant created,
    Instant updated) {

  /**
   * Maps an {@link AccompanyingDocument} entity to a DTO.
   *
   * @param entity the entity to map; must not be {@code null}
   * @return a new {@code AccompanyingDocumentDto} populated from the entity
   */
  public static AccompanyingDocumentDto from(AccompanyingDocument entity) {
    Objects.requireNonNull(entity, "entity must not be null");
    return new AccompanyingDocumentDto(
        entity.getId(),
        entity.getNotificationReferenceNumber(),
        entity.getUploadId(),
        entity.getCorrelationId(),
        entity.getDocumentType(),
        entity.getDocumentReference(),
        entity.getDateOfIssue(),
        entity.getScanStatus(),
        entity.isClaimed(),
        entity.getFiles().stream().map(UploadedFileDto::from).toList(),
        entity.getCreated(),
        entity.getUpdated());
  }
}
