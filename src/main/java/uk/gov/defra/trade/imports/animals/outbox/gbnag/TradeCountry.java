package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import uk.gov.defra.trade.imports.animals.notification.Origin;

public record TradeCountry(
    CodedValue code,
    TradeCountrySubDivision subordinateTradeCountrySubDivision
) {

    static TradeCountry from(Origin origin) {
        if (origin == null || origin.getCountryCode() == null) {
            return null;
        }
        return new TradeCountry(CodedValue.of(origin.getCountryCode()), null);
    }
}
