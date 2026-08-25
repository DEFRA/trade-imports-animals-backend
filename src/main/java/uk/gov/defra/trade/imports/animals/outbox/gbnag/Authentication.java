package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.ArrayList;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;

public record Authentication(List<Clause> includedClause) {

    public Authentication {
        includedClause = List.copyOf(includedClause);
    }

    static Authentication from(NotificationAggregate notificationAggregate) {
        Notification notification = notificationAggregate.getNotification();
        String reasonForImport = notification != null ? notification.getReasonForImport() : null;
        AdditionalDetails additionalDetails = notification != null ? notification.getAdditionalDetails() : null;

        List<Clause> clauses = new ArrayList<>();
        clauses.add(Clause.purpose(reasonForImport));
        if (Clause.INTERNAL_MARKET.equals(reasonForImport)) {
            clauses.add(Clause.internalMarketPurpose());
        }
        clauses.add(Clause.goodsCertifiedAs(
            additionalDetails != null ? additionalDetails.getCertifiedFor() : null));
        return new Authentication(clauses);
    }
}
