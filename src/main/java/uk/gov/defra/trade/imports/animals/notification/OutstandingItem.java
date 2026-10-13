package uk.gov.defra.trade.imports.animals.notification;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;

/** Something a submitted notification still owes before the import can proceed. */
public enum OutstandingItem {
    /** No ITAHC or health certificate has passed its virus scan. */
    HEALTH_CERTIFICATE;

    private static final Set<DocumentType> HEALTH_CERTIFICATE_TYPES =
        EnumSet.of(DocumentType.ITAHC, DocumentType.HEALTH_CERTIFICATE);

    /**
     * What a submission with these accompanying documents still owes, in declaration order.
     *
     * @param documents the notification's accompanying documents; {@code null} counts as none
     * @return the outstanding items, empty when nothing is owed
     */
    static List<OutstandingItem> outstandingFor(List<AccompanyingDocument> documents) {
        boolean hasHealthCertificate = documents != null && documents.stream()
            .anyMatch(document -> document.getScanStatus() == ScanStatus.COMPLETE
                && HEALTH_CERTIFICATE_TYPES.contains(document.getDocumentType()));
        return hasHealthCertificate ? List.of() : List.of(HEALTH_CERTIFICATE);
    }
}
