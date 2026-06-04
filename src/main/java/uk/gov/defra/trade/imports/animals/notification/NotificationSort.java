package uk.gov.defra.trade.imports.animals.notification;

import org.springframework.data.domain.Sort;

/**
 * Parses the {@code sort} query parameter sent by the frontend
 * (e.g. {@code arrivalDate,desc}, {@code createdAt,asc}).
 */
public final class NotificationSort {

    private static final String ARRIVAL_DATE_FIELD = "transport.arrivalDate";
    private static final String CREATED_AT_FIELD = "created";

    private NotificationSort() {
    }

    public static Sort toSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return defaultSort();
        }

        String[] parts = sortParam.split(",");
        if (parts.length != 2) {
            return defaultSort();
        }

        String sortField = parts[0].trim();
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(parts[1].trim())
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;

        return switch (sortField) {
            case "arrivalDate" -> Sort.by(sortDirection, ARRIVAL_DATE_FIELD);
            case "createdAt" -> Sort.by(sortDirection, CREATED_AT_FIELD);
            default -> defaultSort();
        };
    }

    private static Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, ARRIVAL_DATE_FIELD);
    }
}
