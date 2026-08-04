package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Commodity;

public record NotificationFulfilmentsPageResponse(
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<Item> items) {

    public NotificationFulfilmentsPageResponse {
        items = List.copyOf(items);
    }

    public static NotificationFulfilmentsPageResponse from(
        int page,
        int size,
        long totalElements,
        List<Item> items) {
        return new NotificationFulfilmentsPageResponse(
            page,
            size,
            totalElements,
            (int) Math.ceilDiv(totalElements, size),
            items);
    }

    public record Item(
        String id,
        NotificationFulfilmentsStatus status,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        String reference,
        Commodity commodityDisplay,
        String originCountryCode,
        LocalDate arrivalDate,
        String consignorName,
        String consigneeName) {
    }
}
