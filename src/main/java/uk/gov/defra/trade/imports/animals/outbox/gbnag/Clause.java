package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record Clause(String identifier, String content, String urlId) {

    static final String INTERNAL_MARKET = "INTERNAL_MARKET";

    private static final String PURPOSE = "PURPOSE";
    private static final String INTERNAL_MARKET_PURPOSE = "INTERNAL_MARKET_PURPOSE";
    private static final String GOODS_CERTIFIED_AS = "GOODS_CERTIFIED_AS";

    static Clause purpose(String reasonForImport) {
        return new Clause(PURPOSE, reasonForImport, null);
    }

    static Clause internalMarketPurpose() {
        return new Clause(INTERNAL_MARKET_PURPOSE, null, null);
    }

    static Clause goodsCertifiedAs(String certifiedFor) {
        return new Clause(GOODS_CERTIFIED_AS, certifiedFor, null);
    }
}
