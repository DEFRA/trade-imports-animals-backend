package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;

/** Resolves the canonical commodities list from either {@code commodities} or legacy {@code commodity}. */
public final class NotificationCommodities {

    private NotificationCommodities() {}

    public static List<CommodityComplement> entries(Notification notification) {
        if (notification == null) {
            return List.of();
        }
        if (notification.getCommodities() != null && !notification.getCommodities().isEmpty()) {
            return notification.getCommodities();
        }
        return fromLegacyCommodity(notification.getCommodity());
    }

    private static List<CommodityComplement> fromLegacyCommodity(Commodity commodity) {
        if (commodity == null || commodity.getCommodityComplement() == null) {
            return List.of();
        }
        String rootName = commodity.getName();
        return commodity.getCommodityComplement().stream()
            .map(complement -> CommodityComplement.builder()
                .name(complement.getName() != null ? complement.getName() : rootName)
                .commodityCode(complement.getCommodityCode())
                .typeOfCommodity(complement.getTypeOfCommodity())
                .totalNoOfAnimals(complement.getTotalNoOfAnimals())
                .totalNoOfPackages(complement.getTotalNoOfPackages())
                .species(complement.getSpecies())
                .build())
            .toList();
    }
}
