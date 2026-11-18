package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;

/** What a submitted notification still owes. */
public enum OutstandingItem {
    ITAHC;

    /**
     * Works out what is still owed from the documents the journey claimed that passed their virus
     * scan. The ITAHC is owed until one of them is an ITAHC.
     */
    public static List<OutstandingItem> outstandingAmong(List<AccompanyingDocument> claimedCleanDocuments) {
        boolean hasItahc = claimedCleanDocuments.stream()
            .anyMatch(document -> document.getDocumentType() == DocumentType.ITAHC);
        return hasItahc ? List.of() : List.of(ITAHC);
    }
}
