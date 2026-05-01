package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Read-only projection of an {@link AccompanyingDocument} for API responses.
 *
 * <p>Use {@link #from(AccompanyingDocument)} to create an instance from the entity.
 */
public record AccompanyingDocumentDto(
    String id,
    String notificationReferenceNumber,
    String uploadId,
    DocumentType documentType,
    String documentReference,
    Instant dateOfIssue,
    ScanStatus scanStatus,
    List<UploadedFileDto> files,
    LocalDateTime created,
    LocalDateTime updated) {

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
        entity.getDocumentType(),
        entity.getDocumentReference(),
        entity.getDateOfIssue(),
        entity.getScanStatus(),
        entity.getFiles().stream().map(UploadedFileDto::from).toList(),
        entity.getCreated() != null ? LocalDateTime.ofInstant(entity.getCreated(), ZoneOffset.UTC) : null,
        entity.getUpdated() != null ? LocalDateTime.ofInstant(entity.getUpdated(), ZoneOffset.UTC) : null);
  }
}
