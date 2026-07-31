package uk.gov.defra.trade.imports.plantproducts.notification;

import java.util.List;

public record PlantProductsNotificationPageResponse(
    List<PlantProductsNotificationDto> content,
    int page,
    int pageSize,
    long totalElements,
    int totalPages) {

    public PlantProductsNotificationPageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
