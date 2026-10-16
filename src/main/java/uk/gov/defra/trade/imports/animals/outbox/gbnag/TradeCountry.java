package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Origin;

/**
 * A country on the consignment. Only the origin country carries the two facts that say where in
 * the country the animals came from: the subdivision, as the region of origin entry in
 * {@code subordinateTradeCountrySubDivision}, and the region of origin code from the health
 * certificate, which is a disease zone and cannot be derived from the subdivision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
            regionOfOriginCode(origin.getRequiresRegionCode(), origin.getRegionOfOriginCode()));
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

    // Null, not an empty list: NON_NULL omits a null field, where an empty list would stay [].
    @SuppressWarnings("java:S1168")
    private static List<TradeCountrySubDivision> subDivisions(TradeCountrySubDivision subDivision) {
        return subDivision != null ? List.of(subDivision) : null;
    }

    // The frontend keeps a code entered before the trader answered "no", so the answer decides
    // whether the code is published, not whether one is stored.
    private static String regionOfOriginCode(String requiresRegionCode, String code) {
        if (!"yes".equalsIgnoreCase(requiresRegionCode) || code == null || code.isBlank()) {
            return null;
        }
        return code;
    }
}
