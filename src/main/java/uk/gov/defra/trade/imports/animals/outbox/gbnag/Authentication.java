package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.ArrayList;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Notification;

public record Authentication(List<Clause> includedClause) {

    public Authentication {
        includedClause = List.copyOf(includedClause);
    }

    static Authentication from(Notification notification, String referenceNumber) {
        String reasonForImport = notification.getReasonForImport();
        AdditionalDetails additionalDetails = notification.getAdditionalDetails();

        List<Clause> clauses = new ArrayList<>();
        clauses.add(Clause.purpose(reasonForImport, referenceNumber));
        if (Clause.INTERNAL_MARKET_REASON.equals(reasonForImport)) {
            clauses.add(Clause.internalMarketPurpose(notification.getPurposeInInternalMarket(), referenceNumber));
        }
        clauses.add(Clause.goodsCertifiedAs(
            additionalDetails != null ? additionalDetails.getCertifiedFor() : null, referenceNumber));
        return new Authentication(clauses);
    }
}
