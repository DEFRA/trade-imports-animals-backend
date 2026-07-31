package uk.gov.defra.trade.imports.plantproducts.notification;

import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.control.DeepClone;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, mappingControl = DeepClone.class)
public interface PlantProductsNotificationContentSnapshotMapper {

    @BeanMapping(ignoreUnmappedSourceProperties = {
        "id", "referenceNumber", "chedType", "status", "ownership",
        "created", "updated", "submittedBaseline", "expireAt"
    })
    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    @Mapping(target = "nominatedContacts", source = "nominatedContacts", qualifiedByName = "copyNominatedContacts")
    PlantProductsNotificationContentSnapshot capture(PlantProductsNotification source);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "referenceNumber", ignore = true)
    @Mapping(target = "chedType", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "ownership", ignore = true)
    @Mapping(target = "created", ignore = true)
    @Mapping(target = "updated", ignore = true)
    @Mapping(target = "submittedBaseline", ignore = true)
    @Mapping(target = "expireAt", ignore = true)
    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    @Mapping(target = "nominatedContacts", source = "nominatedContacts", qualifiedByName = "copyNominatedContacts")
    void restore(PlantProductsNotificationContentSnapshot snapshot, @MappingTarget PlantProductsNotification target);

    @Named("copyCommodity")
    default PlantProductsCommodity copyCommodity(PlantProductsCommodity source) {
        if (source == null) {
            return null;
        }
        return PlantProductsCommodity.builder()
            .name(source.getName())
            .inputMethod(source.getInputMethod())
            .commodityComplement(commodityLines(source.getCommodityComplement()))
            .build();
    }

    default List<CommodityLine> commodityLines(List<CommodityLine> source) {
        if (source == null) {
            return List.of();
        }
        return mapCommodityLineList(source);
    }

    List<CommodityLine> mapCommodityLineList(List<CommodityLine> source);

    @Named("copyNominatedContacts")
    default List<PlantProductsContact> copyNominatedContacts(List<PlantProductsContact> source) {
        if (source == null) {
            return List.of();
        }
        return mapContactList(source);
    }

    List<PlantProductsContact> mapContactList(List<PlantProductsContact> source);
}
