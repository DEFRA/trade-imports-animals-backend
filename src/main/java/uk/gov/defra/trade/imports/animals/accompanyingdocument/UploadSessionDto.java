package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * A recorded cdp-uploader upload session.
 *
 * @param notificationReferenceNumber the parent notification's reference number; never null
 * @param uploadId                    the identifier cdp-uploader assigned the session; never null
 * @param correlationId               the handle the session was opened under; never null
 */
@Schema(description = "A cdp-uploader upload session recorded against a notification")
public record UploadSessionDto(

    @Schema(description = "Reference number of the notification the session belongs to",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String notificationReferenceNumber,

    @Schema(description = "Identifier cdp-uploader assigned the session",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String uploadId,

    @Schema(description = "Handle the session was opened under, echoed back by the scan callback",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String correlationId) {

  public UploadSessionDto {
    Objects.requireNonNull(notificationReferenceNumber, "notificationReferenceNumber must not be null");
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    Objects.requireNonNull(correlationId, "correlationId must not be null");
  }

  /**
   * Maps a stored session to its DTO.
   *
   * @param session the stored session; must not be null
   * @return the DTO
   */
  public static UploadSessionDto from(UploadSession session) {
    Objects.requireNonNull(session, "session must not be null");
    return new UploadSessionDto(
        session.getNotificationReferenceNumber(),
        session.getUploadId(),
        session.getCorrelationId());
  }
}
