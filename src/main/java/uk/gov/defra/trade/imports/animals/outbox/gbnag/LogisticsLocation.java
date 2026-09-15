package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record LogisticsLocation(
    String identifier,
    String urlId,
    String name,
    String typeCode,
    TradeAddress postalAddress
) {

    private static final String CPH_REGISTER = "https://refdata.tbc.defra.gov.uk/cph_number";

    static LogisticsLocation from(String identifier) {
        return identifier != null ? new LogisticsLocation(identifier, null, null, null, null) : null;
    }

    /**
     * The destination holding identified by its County Parish Holding number. The schema also
     * requires a postal address here, but nothing collected says which address belongs to the
     * holding, so it is left unset rather than guessed.
     */
    static LogisticsLocation cph(String cphNumber) {
        if (cphNumber == null || cphNumber.isBlank()) {
            return null;
        }
        return new LogisticsLocation(cphNumber, CPH_REGISTER, null, null, null);
    }
}
