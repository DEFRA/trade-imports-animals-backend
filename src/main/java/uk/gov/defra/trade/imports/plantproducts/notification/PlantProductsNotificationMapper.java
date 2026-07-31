package uk.gov.defra.trade.imports.plantproducts.notification;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, unmappedSourcePolicy = ReportingPolicy.ERROR)
public interface PlantProductsNotificationMapper {

    @BeanMapping(ignoreUnmappedSourceProperties = {"submittedBaseline", "expireAt"})
    @Mapping(target = "accompanyingDocuments", ignore = true)
    PlantProductsNotificationResponse toResponse(PlantProductsNotification notification);

    @BeanMapping(ignoreUnmappedSourceProperties = {"id", "submittedBaseline", "expireAt"})
    PlantProductsNotificationDto toDto(PlantProductsNotification notification);

    @BeanMapping(ignoreUnmappedSourceProperties = {
        "referenceNumber", "chedType", "status", "ownership", "created", "updated"
    })
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "referenceNumber", ignore = true)
    @Mapping(target = "chedType", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "ownership", ignore = true)
    @Mapping(target = "created", ignore = true)
    @Mapping(target = "updated", ignore = true)
    @Mapping(target = "submittedBaseline", ignore = true)
    @Mapping(target = "expireAt", ignore = true)
    void applyContent(PlantProductsNotificationDto dto, @MappingTarget PlantProductsNotification notification);
}
