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
        "created", "updated", "copyIdempotencyKey", "copySourceReference",
        "submittedBaseline", "expireAt"
    })
    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    @Mapping(target = "nominatedContacts", source = "nominatedContacts", qualifiedByName = "copyNominatedContacts")
    @Mapping(target = "transport", source = "transport", qualifiedByName = "copyTransport")
    PlantProductsNotificationContentSnapshot capture(PlantProductsNotification source);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "referenceNumber", ignore = true)
    @Mapping(target = "chedType", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "ownership", ignore = true)
    @Mapping(target = "created", ignore = true)
    @Mapping(target = "updated", ignore = true)
    @Mapping(target = "copyIdempotencyKey", ignore = true)
    @Mapping(target = "copySourceReference", ignore = true)
    @Mapping(target = "submittedBaseline", ignore = true)
    @Mapping(target = "expireAt", ignore = true)
    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    @Mapping(target = "nominatedContacts", source = "nominatedContacts", qualifiedByName = "copyNominatedContacts")
    @Mapping(target = "transport", source = "transport", qualifiedByName = "copyTransport")
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
        return source.stream().map(this::copyCommodityLine).toList();
    }

    default CommodityLine copyCommodityLine(CommodityLine source) {
        if (source == null) {
            return null;
        }
        return CommodityLine.builder()
            .uniqueComplementId(source.getUniqueComplementId())
            .commodityCode(source.getCommodityCode())
            .commodityDescription(source.getCommodityDescription())
            .numberOfPackages(source.getNumberOfPackages())
            .packageType(source.getPackageType())
            .quantity(source.getQuantity())
            .quantityType(source.getQuantityType())
            .netWeight(source.getNetWeight())
            .controlledAtmosphereContainer(source.getControlledAtmosphereContainer())
            .finishedOrPropagated(source.getFinishedOrPropagated())
            .intendedForFinalUsers(source.getIntendedForFinalUsers())
            .testAndTrial(source.getTestAndTrial())
            .species(species(source.getSpecies()))
            .build();
    }

    default List<PlantSpecies> species(List<PlantSpecies> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream().map(this::copyPlantSpecies).toList();
    }

    default PlantSpecies copyPlantSpecies(PlantSpecies source) {
        if (source == null) {
            return null;
        }
        return PlantSpecies.builder()
            .eppoCode(source.getEppoCode())
            .genusAndSpecies(source.getGenusAndSpecies())
            .speciesId(source.getSpeciesId())
            .varieties(varieties(source.getVarieties()))
            .build();
    }

    default List<SpeciesVariety> varieties(List<SpeciesVariety> source) {
        if (source == null) {
            return List.of();
        }
        return mapVarietyList(source);
    }

    List<SpeciesVariety> mapVarietyList(List<SpeciesVariety> source);

    @Named("copyNominatedContacts")
    default List<PlantProductsContact> copyNominatedContacts(List<PlantProductsContact> source) {
        if (source == null) {
            return List.of();
        }
        return mapContactList(source);
    }

    List<PlantProductsContact> mapContactList(List<PlantProductsContact> source);

    @Named("copyTransport")
    default PlantProductsTransport copyTransport(PlantProductsTransport source) {
        if (source == null) {
            return null;
        }
        return PlantProductsTransport.builder()
            .borderControlPost(source.getBorderControlPost())
            .inspectionPremises(source.getInspectionPremises())
            .meansOfTransport(source.getMeansOfTransport())
            .transportIdentification(source.getTransportIdentification())
            .transportDocumentReference(source.getTransportDocumentReference())
            .arrivalDate(source.getArrivalDate())
            .arrivalTime(source.getArrivalTime())
            .usesContainers(source.getUsesContainers())
            .containers(containers(source.getContainers()))
            .build();
    }

    default List<TransportContainer> containers(List<TransportContainer> source) {
        if (source == null) {
            return List.of();
        }
        return mapContainerList(source);
    }

    List<TransportContainer> mapContainerList(List<TransportContainer> source);
}
