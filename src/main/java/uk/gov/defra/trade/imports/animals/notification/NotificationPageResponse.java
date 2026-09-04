package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Paginated notification-shape list response. Items are the {@link NotificationView} projection
 * serialized directly — no intermediate DTO — so the wire response carries exactly the fields the
 * projection exposes. Note: since the aggregate refactor {@link NotificationView} is an open
 * projection, so the underlying Mongo query does load the full aggregate document per row
 * (including {@code fulfilments}); see {@link NotificationView} for the trade-off.
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
        pageResult.getContent().stream().map(NotificationView::forDashboard).toList(),
        pageResult.getNumber() + 1,
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }
}
