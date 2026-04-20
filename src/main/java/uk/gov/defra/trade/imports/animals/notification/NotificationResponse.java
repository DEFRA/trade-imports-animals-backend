package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;

/**
 * API response representing a fully hydrated notification, including its accompanying documents.
 *
 * <p>Accompanying documents are fetched separately from the {@code accompanying_documents}
 * collection and assembled here at the service layer, avoiding the need for a two-way reference
 * or {@code @DBRef} in the domain model.
 *
 * <p>Use {@link #from(Notification, List)} to construct an instance from domain objects.
 */
public record NotificationResponse(
    String id,
    String referenceNumber,
    Origin origin,
    Commodity commodity,
    String reasonForImport,
    AdditionalDetails additionalDetails,
    String cphNumber,
    LocalDateTime created,
    LocalDateTime updated,
    List<AccompanyingDocumentDto> accompanyingDocuments) {

  /**
   * Assembles a {@code NotificationResponse} from a {@link Notification} entity and its associated
   * {@link AccompanyingDocument} list.
   *
   * @param notification the notification entity; must not be {@code null}
   * @param documents    the accompanying documents for this notification; may be empty
   * @return a fully populated response record
   */
  public static NotificationResponse from(
      Notification notification, List<AccompanyingDocument> documents) {
    return new NotificationResponse(
        notification.getId(),
        notification.getReferenceNumber(),
        notification.getOrigin(),
        notification.getCommodity(),
        notification.getReasonForImport(),
        notification.getAdditionalDetails(),
        notification.getCphNumber(),
        notification.getCreated(),
        notification.getUpdated(),
        documents.stream().map(AccompanyingDocumentDto::from).toList());
  }
}
