package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Origin;

public record TradeCountry(
    CodedValue code,
    TradeCountrySubDivision subordinateTradeCountrySubDivision
) {

    static TradeCountry from(Origin origin) {
        if (origin == null || origin.getCountryCode() == null) {
            return null;
        }
        return new TradeCountry(
            CodedValue.of(origin.getCountryCode()),
            TradeCountrySubDivision.regionOfOrigin(origin.getRegionOfOriginCode()));
    }

    @SuppressWarnings("java:S1168")
    static List<TradeCountry> fromCountryCodes(List<String> countryCodes) {
        if (countryCodes == null || countryCodes.isEmpty()) {
            return null;
        }
        return countryCodes.stream()
            .map(code -> new TradeCountry(CodedValue.of(code), null))
            .toList();
    }
}
