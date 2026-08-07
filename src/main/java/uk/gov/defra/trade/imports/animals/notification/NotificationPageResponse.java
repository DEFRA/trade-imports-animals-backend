package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.data.domain.Page;

public record NotificationPageResponse(
    List<NotificationDto> content,
    int page,
    int size,
    int numberOfElements,
    long totalElements,
    int totalPages) {

  public static NotificationPageResponse from(Page<Notification> pageResult) {
    return from(pageResult, UnaryOperator.identity());
  }

  /**
   * As {@link #from(Page)}, with each party passed through {@code partyResolver} on the way out —
   * the hook that turns an address-book reference into the record's current details (see
   * {@link PartyResolver}). The stored notifications are not modified.
   */
  public static NotificationPageResponse from(
      Page<Notification> pageResult, UnaryOperator<ConsignmentParty> partyResolver) {
    return new NotificationPageResponse(
        pageResult.getContent().stream()
            .map(notification -> toDto(notification, partyResolver))
            .toList(),
        pageResult.getNumber() + 1,
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }

  private static NotificationDto toDto(
      Notification notification, UnaryOperator<ConsignmentParty> partyResolver) {
    return NotificationDto.builder()
        .referenceNumber(notification.getReferenceNumber())
        .origin(notification.getOrigin())
        .commodity(notification.getCommodity())
        .reasonForImport(notification.getReasonForImport())
        .additionalDetails(notification.getAdditionalDetails())
        .placeOfOrigin(partyResolver.apply(notification.getPlaceOfOrigin()))
        .consignor(partyResolver.apply(notification.getConsignor()))
        .consignee(partyResolver.apply(notification.getConsignee()))
        .importer(partyResolver.apply(notification.getImporter()))
        .destination(partyResolver.apply(notification.getDestination()))
        .consignment(partyResolver.apply(notification.getConsignment()))
        .cphNumber(notification.getCphNumber())
        .transport(notification.getTransport())
        .status(notification.getStatus())
        .created(notification.getCreated())
        .updated(notification.getUpdated())
        .build();
  }
}
