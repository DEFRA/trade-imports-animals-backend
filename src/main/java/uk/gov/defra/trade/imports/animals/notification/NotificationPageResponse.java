package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Paginated notification-shape list response. Sourced from the {@link NotificationView} projection
 * (never from the full {@link Notification} aggregate) so the opaque {@code fulfilments} payload is
 * not loaded for the list endpoint — see the AC-mandated projection separation on
 * {@link NotificationMapper}.
 */
public record NotificationPageResponse(
    List<NotificationDto> content,
    int page,
    int size,
    int numberOfElements,
    long totalElements,
    int totalPages) {

  public static NotificationPageResponse from(Page<NotificationView> pageResult) {
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

  private static NotificationDto toDto(NotificationView view) {
    return NotificationDto.builder()
        .referenceNumber(view.getReferenceNumber())
        .origin(view.getOrigin())
        .commodity(view.getCommodity())
        .reasonForImport(view.getReasonForImport())
        .additionalDetails(view.getAdditionalDetails())
        .placeOfOrigin(view.getPlaceOfOrigin())
        .consignor(view.getConsignor())
        .consignee(view.getConsignee())
        .importer(view.getImporter())
        .destination(view.getDestination())
        .consignment(view.getConsignment())
        .cphNumber(view.getCphNumber())
        .transport(view.getTransport())
        .status(view.getStatus())
        .created(view.getCreated())
        .updated(view.getUpdated())
        .build();
  }
}
