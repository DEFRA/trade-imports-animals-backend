package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record TradeCountrySubDivision(
    String identifier,
    String urlId,
    FunctionTypeCode functionTypeCode
) {

    // UNCL3227 location function "Region of origin".
    private static final String REGION_OF_ORIGIN = "106";

    public record FunctionTypeCode(String content) {}

    static TradeCountrySubDivision regionOfOrigin(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        return new TradeCountrySubDivision(regionCode, null, new FunctionTypeCode(REGION_OF_ORIGIN));
    }
}
