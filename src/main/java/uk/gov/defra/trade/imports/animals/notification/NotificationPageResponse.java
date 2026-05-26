package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.data.domain.Page;

public record NotificationPageResponse(
    List<Notification> content,
    int page,
    int size,
    int numberOfElements,
    long totalElements,
    int totalPages) {

  public static NotificationPageResponse from(Page<Notification> pageResult) {
    return new NotificationPageResponse(
        pageResult.getContent(),
        pageResult.getNumber(),
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }
}
