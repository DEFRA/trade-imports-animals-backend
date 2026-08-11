package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Paginated notification-shape list response. Items are the {@link NotificationView} projection
 * serialized directly — no intermediate DTO — so the wire response carries exactly the fields the
 * projection exposes and the opaque {@code fulfilments} payload is never loaded from Mongo.
 */
public record NotificationPageResponse(
    List<NotificationView> content,
    int page,
    int size,
    int numberOfElements,
    long totalElements,
    int totalPages) {

  public static NotificationPageResponse from(Page<NotificationView> pageResult) {
    return new NotificationPageResponse(
        pageResult.getContent(),
        pageResult.getNumber() + 1,
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }
}
