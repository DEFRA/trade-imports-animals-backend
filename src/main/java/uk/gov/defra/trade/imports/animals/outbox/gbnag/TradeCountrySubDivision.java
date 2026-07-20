package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record TradeCountrySubDivision(
    String identifier,
    String urlId,
    FunctionTypeCode functionTypeCode
) {

    public record FunctionTypeCode(String content) {}
}
