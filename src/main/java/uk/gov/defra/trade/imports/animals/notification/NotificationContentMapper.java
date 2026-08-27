package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.control.DeepClone;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, mappingControl = DeepClone.class)
public interface NotificationContentMapper {

    @Mapping(target = "commodity", source = "commodity", qualifiedByName = "copyCommodity")
    Notification deepClone(Notification source);

    // Normalise commodityComplement null to empty list so snapshots never hold null collections.
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
