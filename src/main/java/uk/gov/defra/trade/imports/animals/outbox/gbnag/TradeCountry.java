package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Origin;

/**
 * A country on the consignment. Only the origin country carries the two facts that say where in
 * the country the animals came from: the subdivision, as the region of origin entry in
 * {@code subordinateTradeCountrySubDivision}, and the region of origin code from the health
 * certificate, which is a disease zone and cannot be derived from the subdivision.
 */
public record TradeCountry(
    CodedValue code,
    List<TradeCountrySubDivision> subordinateTradeCountrySubDivision,
    String regionOfOriginCode
) {

    static TradeCountry from(Origin origin) {
        if (origin == null || origin.getCountryCode() == null) {
            return null;
        }
        return new TradeCountry(
            CodedValue.of(origin.getCountryCode()),
            subDivisions(TradeCountrySubDivision.regionOfOrigin(origin.getCountrySubdivisionCode())),
            regionOfOriginCode(origin.getRegionOfOriginCode()));
    }

    @SuppressWarnings("java:S1168")
    static List<TradeCountry> fromCountryCodes(List<String> countryCodes) {
        if (countryCodes == null || countryCodes.isEmpty()) {
            return null;
        }
        return countryCodes.stream()
            .map(code -> new TradeCountry(CodedValue.of(code), null, null))
            .toList();
    }

    // Null, not an empty list: absent is null throughout this record, and the pending NON_NULL
    // serialisation change turns null into an omitted field, where an empty list would stay [].
    @SuppressWarnings("java:S1168")
    private static List<TradeCountrySubDivision> subDivisions(TradeCountrySubDivision subDivision) {
        return subDivision != null ? List.of(subDivision) : null;
    }

    private static String regionOfOriginCode(String code) {
        return code == null || code.isBlank() ? null : code;
    }
}
