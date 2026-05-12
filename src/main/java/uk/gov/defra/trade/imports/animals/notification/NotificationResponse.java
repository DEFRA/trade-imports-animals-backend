package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.Builder;
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
@Builder
public record NotificationResponse(
    String id,
    String referenceNumber,
    Origin origin,
    Commodity commodity,
    String reasonForImport,
    AdditionalDetails additionalDetails,
    String cphNumber,
    Transport transport,
    NotificationStatus status,
    LocalDateTime created,
    LocalDateTime updated,
    List<AccompanyingDocumentDto> accompanyingDocuments) {

  /**
   * Assembles a {@code NotificationResponse} from a {@link Notification} entity and its associated
   * {@link AccompanyingDocument} list.
   *
   * @param notification the notification entity; must not be {@code null}
   * @param documents    the accompanying documents for this notification; must not be {@code null},
   *                     may be empty
   * @return a fully populated response record
   */
  public static NotificationResponse from(
      Notification notification, List<AccompanyingDocument> documents) {
    Objects.requireNonNull(notification, "notification");
    Objects.requireNonNull(documents, "documents");
    return NotificationResponse.builder()
        .id(notification.getId())
        .referenceNumber(notification.getReferenceNumber())
        .origin(notification.getOrigin())
        .commodity(notification.getCommodity())
        .reasonForImport(notification.getReasonForImport())
        .additionalDetails(notification.getAdditionalDetails())
        .cphNumber(notification.getCphNumber())
        .transport(notification.getTransport())
        .status(notification.getStatus())
        .created(notification.getCreated())
        .updated(notification.getUpdated())
        .accompanyingDocuments(documents.stream().map(AccompanyingDocumentDto::from).toList())
        .build();
  }
}
