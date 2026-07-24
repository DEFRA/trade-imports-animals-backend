package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.control.DeepClone;

/**
 * Deep-copies amendable notification fields for amend cancel/restore.
 *
 * <p>{@code commodityComplement} is normalised to an empty list when null so snapshots never
 * hold a null collection.
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, mappingControl = DeepClone.class)
public interface NotificationContentSnapshotMapper {

    @BeanMapping(ignoreUnmappedSourceProperties = {
        "id", "owner", "referenceNumber", "status", "created", "updated", "submittedBaseline"
    })
    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    NotificationContentSnapshot capture(Notification source);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "referenceNumber", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "created", ignore = true)
    @Mapping(target = "updated", ignore = true)
    @Mapping(target = "submittedBaseline", ignore = true)
    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    void restore(NotificationContentSnapshot snapshot, @MappingTarget Notification target);

    @Named("copyCommodity")
    default Commodity copyCommodity(Commodity source) {
        if (source == null) {
            return null;
        }
        return Commodity.builder()
            .name(source.getName())
            .commodityComplement(commodityComplements(source.getCommodityComplement()))
            .build();
    }

    default List<CommodityComplement> commodityComplements(List<CommodityComplement> source) {
        if (source == null) {
            return List.of();
        }
        return mapCommodityComplementList(source);
    }

    List<CommodityComplement> mapCommodityComplementList(List<CommodityComplement> source);
}
