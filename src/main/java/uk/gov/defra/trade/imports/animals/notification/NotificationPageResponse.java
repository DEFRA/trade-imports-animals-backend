package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.data.domain.Page;

public record NotificationPageResponse(
    List<NotificationDto> content,
    int page,
    int size,
    int numberOfElements,
    long totalElements,
    int totalPages) {

  public static NotificationPageResponse from(Page<Notification> pageResult) {
    return new NotificationPageResponse(
        pageResult.getContent().stream()
            .map(NotificationPageResponse::toDto)
            .toList(),
        pageResult.getNumber() + 1,
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }

  private static NotificationDto toDto(Notification notification) {
    return NotificationDto.builder()
        .referenceNumber(notification.getReferenceNumber())
        .origin(notification.getOrigin())
        .commodity(notification.getCommodity())
        .reasonForImport(notification.getReasonForImport())
        .additionalDetails(notification.getAdditionalDetails())
        .placeOfOrigin(notification.getPlaceOfOrigin())
        .consignor(notification.getConsignor())
        .consignee(notification.getConsignee())
        .importer(notification.getImporter())
        .destination(notification.getDestination())
        .consignment(notification.getConsignment())
        .cphNumber(notification.getCphNumber())
        .transport(notification.getTransport())
        .status(notification.getStatus())
        .created(notification.getCreated())
        .updated(notification.getUpdated())
        .build();
  }
}
