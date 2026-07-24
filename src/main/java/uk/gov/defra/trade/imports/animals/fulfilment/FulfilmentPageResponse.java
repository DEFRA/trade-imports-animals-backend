package uk.gov.defra.trade.imports.animals.fulfilment;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;

public record FulfilmentPageResponse(
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<Item> items) {

    public FulfilmentPageResponse {
        items = List.copyOf(items);
    }

    public static FulfilmentPageResponse from(Page<Fulfilment> result) {
        return new FulfilmentPageResponse(
            result.getNumber() + 1,
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages(),
            result.getContent().stream().map(Item::from).toList());
    }

    public record Item(
        String id,
        FulfilmentStatus status,
        LocalDateTime createdAt,
        LocalDateTime submittedAt) {

        private static Item from(Fulfilment fulfilment) {
            return new Item(
                fulfilment.getId(),
                fulfilment.getStatus(),
                fulfilment.getCreatedAt(),
                fulfilment.getSubmittedAt());
        }
    }
}
