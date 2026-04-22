package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.Instant;
import java.util.List;

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
    List<UploadedFile> files,
    Instant created,
    Instant updated) {

  /**
   * Maps an {@link AccompanyingDocument} entity to a DTO.
   *
   * @param entity the entity to map; must not be {@code null}
   * @return a new {@code AccompanyingDocumentDto} populated from the entity
   */
  public static AccompanyingDocumentDto from(AccompanyingDocument entity) {
    return new AccompanyingDocumentDto(
        entity.getId(),
        entity.getNotificationReferenceNumber(),
        entity.getUploadId(),
        entity.getDocumentType(),
        entity.getDocumentReference(),
        entity.getDateOfIssue(),
        entity.getScanStatus(),
        entity.getFiles(),
        entity.getCreated(),
        entity.getUpdated());
  }
}
