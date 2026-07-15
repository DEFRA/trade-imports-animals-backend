package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;

/**
 * API response representing a fully hydrated notification, including its accompanying documents.
 *
 * <p>Accompanying documents are fetched separately from the {@code accompanying_documents}
 * collection and assembled by the service layer after mapping, avoiding the need for a two-way
 * reference or {@code @DBRef} in the domain model.
 *
 * <p>{@code deletedOperatorFields} and {@code unresolvedOperatorFields} are the EUDPA-293 detection
 * surface (design §4.4). They carry only party <em>keys</em> — never operator field values — and are
 * populated by the service layer's existence check on a DRAFT/AMEND read (c-017/c-018). Both are
 * {@code null} (absent) when the check could not run: no {@code operatorId}s present, or the operators
 * service was unreachable. Absence means "no claim", not "verified clean". A tombstoned operator (200
 * + status DELETED) surfaces under {@code deletedOperatorFields}; a 404 (unknown id, or an id in
 * another crn's scope) surfaces under {@code unresolvedOperatorFields} — the two states are kept apart
 * because a 404 is not a deletion.
 */
@Builder(toBuilder = true)
public record NotificationResponse(
    String id,
    String referenceNumber,
    Origin origin,
    Commodity commodity,
    String reasonForImport,
    AdditionalDetails additionalDetails,
    Operator placeOfOrigin,
    Operator consignor,
    Operator consignee,
    Operator importer,
    Operator destination,
    Operator consignment,
    String cphNumber,
    Transport transport,
    NotificationStatus status,
    LocalDateTime created,
    LocalDateTime updated,
    List<AccompanyingDocumentDto> accompanyingDocuments,
    List<String> deletedOperatorFields,
    List<String> unresolvedOperatorFields) {

}
