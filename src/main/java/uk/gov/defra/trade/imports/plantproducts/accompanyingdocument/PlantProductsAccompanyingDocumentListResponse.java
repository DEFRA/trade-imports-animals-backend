package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import java.util.List;

public record PlantProductsAccompanyingDocumentListResponse(
    List<PlantProductsAccompanyingDocumentDto> documents) {

    public PlantProductsAccompanyingDocumentListResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
