package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.data.domain.Page;

public record ReferenceNumberPageResponse(
    List<String> content,
    int page,
    int size,
    int numberOfElements,
    long totalElements,
    int totalPages) {

  public static ReferenceNumberPageResponse from(Page<NotificationReferenceOnly> pageResult) {
    return new ReferenceNumberPageResponse(
        pageResult.getContent().stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .toList(),
        pageResult.getNumber(),
        pageResult.getSize(),
        pageResult.getNumberOfElements(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }
}