package uk.gov.defra.trade.imports.animals.fulfilment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Commodity;

public record FulfilmentPageResponse(
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<Item> items) {

    public FulfilmentPageResponse {
        items = List.copyOf(items);
    }

    public static FulfilmentPageResponse from(
        int page,
        int size,
        long totalElements,
        List<Item> items) {
        return new FulfilmentPageResponse(
            page,
            size,
            totalElements,
            (int) Math.ceilDiv(totalElements, size),
            items);
    }

    public record Item(
        String id,
        FulfilmentStatus status,
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
