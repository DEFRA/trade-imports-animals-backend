package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import org.springframework.data.domain.Sort;

public final class NotificationFulfilmentsSort {

    private static final String ARRIVAL_DATE = "arrivalDate";
    private static final String CREATED_AT = "createdAt";

    private NotificationFulfilmentsSort() {
    }

    public static Sort toSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return defaultSort();
        }

        String[] parts = sortParam.split(",", -1);
        if (parts.length != 2) {
            return defaultSort();
        }

        String field = parts[0].trim();
        String direction = parts[1].trim();
        if (!direction.equalsIgnoreCase("asc") && !direction.equalsIgnoreCase("desc")) {
            return defaultSort();
        }

        Sort.Direction sortDirection = Sort.Direction.fromString(direction);
        return switch (field) {
            case ARRIVAL_DATE, CREATED_AT -> Sort.by(sortDirection, field);
            default -> defaultSort();
        };
    }

    private static Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, ARRIVAL_DATE);
    }
}
