package uk.gov.defra.trade.imports.animals.outbox.gbnag;

public record CodedValue(String value, String urlId, String name) {

    public static CodedValue of(String value, String urlId) {
        return new CodedValue(value, urlId, null);
    }

    public static CodedValue of(String value) {
        return new CodedValue(value, null, null);
    }
}
