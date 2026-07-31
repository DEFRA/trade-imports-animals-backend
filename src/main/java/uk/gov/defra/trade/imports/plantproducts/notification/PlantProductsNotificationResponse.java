package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentDto;

@Builder(toBuilder = true)
public record PlantProductsNotificationResponse(
    String id,
    String referenceNumber,
    String chedType,
    PlantProductsNotificationStatus status,
    Ownership ownership,
    PlantProductsOrigin origin,
    ReasonForImport reasonForImport,
    PlantProductsCommodity commodity,
    PlantProductsAdditionalDetails additionalDetails,
    PlantProductsOperator consignor,
    PlantProductsOperator consignee,
    PlantProductsOperator importer,
    PlantProductsOperator destination,
    PlantProductsOperator packer,
    PlantProductsContact responsiblePerson,
    List<PlantProductsContact> nominatedContacts,
    PlantProductsTransport transport,
    GoodsMovementServices goodsMovementServices,
    Boolean isCuc,
    PlantProductsBilling billing,
    Declaration declaration,
    LocalDateTime created,
    LocalDateTime updated,
    List<PlantProductsAccompanyingDocumentDto> accompanyingDocuments) {

    public PlantProductsNotificationResponse {
        nominatedContacts = nominatedContacts == null ? List.of() : List.copyOf(nominatedContacts);
        accompanyingDocuments = accompanyingDocuments == null ? List.of() : List.copyOf(accompanyingDocuments);
    }
}
