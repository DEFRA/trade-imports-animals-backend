package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, unmappedSourcePolicy = ReportingPolicy.ERROR)
public interface PlantProductsAccompanyingDocumentMapper {

    @BeanMapping(ignoreUnmappedSourceProperties = {"notificationReferenceNumber", "created", "updated"})
    PlantProductsAccompanyingDocumentDto toDto(PlantProductsAccompanyingDocument document);

    @BeanMapping(ignoreUnmappedSourceProperties = {"id"})
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "notificationReferenceNumber", ignore = true)
    @Mapping(target = "created", ignore = true)
    @Mapping(target = "updated", ignore = true)
    PlantProductsAccompanyingDocument toEntity(PlantProductsAccompanyingDocumentDto dto);
}
