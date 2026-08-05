package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import java.time.LocalDate;
import java.util.List;

public record PlantProductsAccompanyingDocumentDto(
    String id,
    String documentType,
    String documentReference,
    LocalDate issueDate,
    List<DocumentFile> files) {

    public PlantProductsAccompanyingDocumentDto {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
