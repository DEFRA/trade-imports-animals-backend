package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;

public record LineTradeDelivery(ProductUnitQuantity productUnitQuantity) {

    public record ProductUnitQuantity(Integer content, String unitCode) {}

    @SuppressWarnings("java:S1168")
    static List<LineTradeDelivery> headCount(Integer totalNoOfAnimals) {
        if (totalNoOfAnimals == null) {
            return null;
        }
        return List.of(new LineTradeDelivery(new ProductUnitQuantity(totalNoOfAnimals, null)));
    }
}
