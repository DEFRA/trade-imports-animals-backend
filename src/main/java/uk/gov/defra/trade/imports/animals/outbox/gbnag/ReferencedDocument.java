package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;

public record ReferencedDocument(
    String typeCode,
    String relationshipTypeCode,
    String identifier,
    String issueDateTime
) {

    /**
     * The transport document for the arrival leg. Only the reference is collected, so its UNTDID
     * 1001 type is inferred from how the consignment travels.
     */
    @SuppressWarnings("java:S1168")
    static List<ReferencedDocument> transportDocument(String reference, MeansOfTransport meansOfTransport) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return List.of(new ReferencedDocument(transportDocumentTypeCode(meansOfTransport), null, reference, null));
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
}
