package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeCountrySubDivision(
    String identifier,
    String urlId,
    FunctionTypeCode functionTypeCode
) {

    // UNCL3227 location function "Region of origin".
    private static final String REGION_OF_ORIGIN = "106";
    private static final String COUNTRY_SUBDIVISIONS = "https://refdata.tbc.defra.gov.uk/country_subdivisions";

    public record FunctionTypeCode(String content) {}

    /**
     * The country subdivision the animals came from, such as the Canary Islands, as the region of
     * origin entry a TRACES CHED uses. The identifier is the MDM subdivision code, and the urlId names
     * the MDM subdivision list, because those codes are not all ISO 3166-2.
     */
    static TradeCountrySubDivision regionOfOrigin(String subdivisionCode) {
        if (subdivisionCode == null || subdivisionCode.isBlank()) {
            return null;
        }
        return new TradeCountrySubDivision(
            subdivisionCode, COUNTRY_SUBDIVISIONS, new FunctionTypeCode(REGION_OF_ORIGIN));
    }
}
