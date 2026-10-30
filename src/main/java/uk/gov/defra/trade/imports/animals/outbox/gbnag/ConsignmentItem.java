package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.Notification;

public record ConsignmentItem(List<TradeLineItem> includedTradeLineItem) {

    @SuppressWarnings("java:S1168")
    static List<ConsignmentItem> from(Notification notification) {
        List<CommodityComplement> entries = notification.getCommodities();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        List<TradeLineItem> lines = entries.stream()
            .flatMap(entry -> TradeLineItem.linesFrom(entry).stream())
            .toList();
        return List.of(new ConsignmentItem(lines));
    }
}
