package uk.gov.defra.trade.imports.animals.notification;

import java.util.ArrayList;
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
    default List<Commodity> copyCommodities(List<Commodity> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        return mapCommodityList(source);
    }

    List<Commodity> mapCommodityList(List<Commodity> source);
}
