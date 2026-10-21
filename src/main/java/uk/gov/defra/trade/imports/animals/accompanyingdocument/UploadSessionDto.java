package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An upload session as the uploading journey sees it: the notification it belongs to and the two
 * identifiers the session is known by.
 *
 * @param notificationReferenceNumber the parent notification reference number
 * @param uploadId                    the identifier cdp-uploader assigned the session
 * @param correlationId               the handle the session was opened under
 */
@Schema(description = "A cdp-uploader upload session recorded against a notification")
public record UploadSessionDto(
    String notificationReferenceNumber,
    String uploadId,
    String correlationId) {

  /**
   * Maps a stored session to its DTO.
   *
   * @param session the stored session
   * @return the DTO
   */
  public static UploadSessionDto from(UploadSession session) {
    return new UploadSessionDto(
        session.getNotificationReferenceNumber(),
        session.getUploadId(),
        session.getCorrelationId());
  }
}
