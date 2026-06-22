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
    List<AccompanyingDocumentDto> accompanyingDocuments) {

}
