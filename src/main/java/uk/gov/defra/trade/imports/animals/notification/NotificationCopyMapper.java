package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a source {@link Notification} to a {@link NotificationDto} applying the AC3
 * field-copy rules for the copy-as-new feature.
 *
 * <p>MapStruct was not used because the {@link CommodityComplement} transformation —
 * retaining only {@code typeOfCommodity} while explicitly nulling all per-animal data —
 * requires a custom element-level mapping method. Encoding the AC3 retain/reset rules
 * in plain Java keeps them co-located, explicit, and directly auditable against the
 * acceptance criteria without MapStruct-generated indirection.
 */
@Component
public class NotificationCopyMapper {

    public NotificationDto toCopyDto(Notification source) {
        return NotificationDto.builder()
            .origin(mapOrigin(source.getOrigin()))
            .commodity(mapCommodity(source.getCommodity()))
            .reasonForImport(source.getReasonForImport())
            .additionalDetails(mapAdditionalDetails(source.getAdditionalDetails()))
            .consignor(mapConsignor(source.getConsignor()))
            .destination(mapDestination(source.getDestination()))
            .cphNumber(source.getCphNumber())
            // transport intentionally omitted — portOfEntry, arrivalDate, transporter not copied (AC3)
            // consignment intentionally omitted — contact address not copied (AC3)
            .build();
    }

    private Consignor mapConsignor(Consignor source) {
        if (source == null) {
            return null;
        }
        return Consignor.builder()
            .name(source.getName())
            .address(source.getAddress())
            .build();
    }

    private Destination mapDestination(Destination source) {
        if (source == null) {
            return null;
        }
        return Destination.builder()
            .name(source.getName())
            .address(source.getAddress())
            .build();
    }

    private Origin mapOrigin(Origin source) {
        if (source == null) {
            return null;
        }
        return Origin.builder()
            .countryCode(source.getCountryCode())
            .requiresRegionCode(source.getRequiresRegionCode())
            // internalReference intentionally omitted (AC3)
            .build();
    }

    private Commodity mapCommodity(Commodity source) {
        if (source == null) {
            return null;
        }
        return Commodity.builder()
            .name(source.getName())
            .commodityComplement(mapComplements(source.getCommodityComplement()))
            .build();
    }

    private List<CommodityComplement> mapComplements(List<CommodityComplement> source) {
        if (source == null) {
            return List.of();
        }
        // totalNoOfAnimals, totalNoOfPackages, species intentionally omitted (AC3)
        return source.stream()
            .map(cc -> CommodityComplement.builder().typeOfCommodity(cc.getTypeOfCommodity()).build())
            .toList();
    }

    private AdditionalDetails mapAdditionalDetails(AdditionalDetails source) {
        if (source == null) {
            return null;
        }
        return AdditionalDetails.builder()
            .certifiedFor(source.getCertifiedFor())
            // unweanedAnimals intentionally omitted (AC3)
            .build();
    }
}
