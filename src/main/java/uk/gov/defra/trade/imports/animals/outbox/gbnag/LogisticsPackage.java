package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;

public record LogisticsPackage(Integer levelCode, String typeCode, Integer itemQuantity) {

    static List<LogisticsPackage> packageCount(Integer totalNoOfPackages) {
        if (totalNoOfPackages == null) {
            return null;
        }
        return List.of(new LogisticsPackage(null, null, totalNoOfPackages));
    }
}
