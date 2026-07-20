package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Commodity;

public record ConsignmentItem(List<TradeLineItem> includedTradeLineItem) {

    @SuppressWarnings("java:S1168")
    static List<ConsignmentItem> from(Commodity commodity) {
        if (commodity == null || commodity.getCommodityComplement() == null) {
            return null;
        }
        List<TradeLineItem> lines = commodity.getCommodityComplement().stream()
            .map(TradeLineItem::from)
            .toList();
        return List.of(new ConsignmentItem(lines));
    }
}
