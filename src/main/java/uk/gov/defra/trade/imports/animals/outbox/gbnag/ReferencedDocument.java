package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferencedDocument(
    String typeCode,
    String urlId,
    String relationshipTypeCode,
    String identifier,
    String issueDateTime
) {

    static final String GBN_AG_DOCUMENT_TYPES = "https://refdata.tbc.defra.gov.uk/gbn-ag-document-types";

    /**
     * The transport document for the arrival leg. Only the reference is collected, so its UNTDID
     * 1001 type is inferred from how the consignment travels.
     */
    @SuppressWarnings("java:S1168")
    static List<ReferencedDocument> transportDocument(String reference, MeansOfTransport meansOfTransport) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return List.of(new ReferencedDocument(transportDocumentTypeCode(meansOfTransport), null, null, reference, null));
    }

    /**
     * An accompanying document, coded from the GBN-AG document-type codelist
     * (schemas/codelists/gbn-ag-document-types.json in trade-imports-schemas). That list copies in
     * the UNTDID 1001 codes it uses, so the urlId always names it, whatever the code.
     */
    static ReferencedDocument accompanyingDocument(AccompanyingDocument document) {
        return new ReferencedDocument(
            accompanyingDocumentTypeCode(document.getDocumentType()),
            GBN_AG_DOCUMENT_TYPES,
            null,
            document.getDocumentReference(),
            issueDate(document));
    }

    private static String transportDocumentTypeCode(MeansOfTransport meansOfTransport) {
        if (meansOfTransport == null) {
            return null;
        }
        return switch (meansOfTransport) {
            case VESSEL -> "705";       // Bill of lading
            case RAILWAY -> "720";      // Rail consignment note
            case ROAD_VEHICLE -> "730"; // Road consignment note
            case AIRPLANE -> "740";     // Air waybill
        };
    }

    // Exhaustive on purpose: a new document type fails to compile until someone decides its code.
    // 916 (Related document) is only for a notifier who chooses "Other", never a default.
    private static String accompanyingDocumentTypeCode(DocumentType documentType) {
        return switch (documentType) {
            case VETERINARY_HEALTH_CERTIFICATE -> "853";
            case HEALTH_CERTIFICATE -> "636";
            case AIR_WAYBILL -> "740";
            case SEA_WAYBILL -> "710";
            case RAIL_WAYBILL -> "720";
            case BILL_OF_LADING -> "705";
            case COMMERCIAL_INVOICE -> "380";
            case ITAHC -> "856";                                    // Inspection certificate, as TRACES codes the EU INTRA certificate
            case IMPORT_PERMIT -> "911";                            // Import licence
            case LETTER_OF_AUTHORITY -> "GBN1";
            case CATCH_CERTIFICATE -> "GBN2";
            case LABORATORY_SAMPLING_RESULTS_FOR_AFLATOXIN -> "4";  // Test report
            case JOURNEY_LOG -> "GBN3";
            case OTHER -> "916";                                    // Related document
        };
    }

    // dateOfIssue is stored as midnight UTC to stand for a date, so its UTC date is the issue date.
    private static String issueDate(AccompanyingDocument document) {
        return document.getDateOfIssue() != null
            ? LocalDate.ofInstant(document.getDateOfIssue(), ZoneOffset.UTC).toString()
            : null;
    }
}
