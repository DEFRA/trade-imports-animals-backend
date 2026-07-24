package uk.gov.defra.trade.imports.animals.ownership;

public final class OwnerHeaders {

    public static final String OWNER_ID = "X-Owner-Id";
    public static final String OWNER_ORGANISATION = "X-Owner-Organisation";

    private OwnerHeaders() {
    }

    public static Owner toOwner(String sub, String organisation) {
        return new Owner(sub, organisation);
    }
}
