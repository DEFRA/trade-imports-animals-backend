package uk.gov.defra.trade.imports.animals.fulfilment;

import org.springframework.data.domain.Sort;

public final class FulfilmentSort {

    private static final String CREATED_AT = "createdAt";
    private static final String SUBMITTED_AT = "submittedAt";

    private FulfilmentSort() {
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
            case CREATED_AT, SUBMITTED_AT -> Sort.by(sortDirection, field);
            default -> defaultSort();
        };
    }

    private static Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, CREATED_AT);
    }
}
