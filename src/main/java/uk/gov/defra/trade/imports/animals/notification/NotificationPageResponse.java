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
            .map(notification -> NotificationDto.builder()
                .referenceNumber(notification.getReferenceNumber())
                .origin(notification.getOrigin())
                .commodity(notification.getCommodity())
                .reasonForImport(notification.getReasonForImport())
                .additionalDetails(notification.getAdditionalDetails())
                .consignor(notification.getConsignor())
                .destination(notification.getDestination())
                .cphNumber(notification.getCphNumber())
                .transport(notification.getTransport())
                .consignment(notification.getConsignment())
                .status(notification.getStatus())
                .created(notification.getCreated())
                .updated(notification.getUpdated())
                .build())
            .toList(),
        pageResult.getNumber(),
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }
}
