package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record LogisticsLocation(
    String identifier,
    String urlId,
    String name,
    String typeCode,
    TradeAddress postalAddress
) {

    static LogisticsLocation from(String identifier) {
        return identifier != null ? new LogisticsLocation(identifier, null, null, null, null) : null;
    }
}
