package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.control.DeepClone;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, mappingControl = DeepClone.class)
public interface NotificationContentMapper {

    @Mapping(target = "commodities", source = "commodities", qualifiedByName = "copyCommodities")
    Notification deepClone(Notification source);

    @Named("copyCommodities")
    default List<CommodityComplement> copyCommodities(List<CommodityComplement> source) {
        if (source == null) {
            return List.of();
        }
        return mapCommodityComplementList(source);
    }

    List<CommodityComplement> mapCommodityComplementList(List<CommodityComplement> source);
}
