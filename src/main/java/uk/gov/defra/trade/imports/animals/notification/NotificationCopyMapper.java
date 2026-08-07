package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a source {@link Notification} to a {@link NotificationDto} for the copy-as-new
 * feature: identification, commodity type, and address details are retained, while
 * logistical fields (transport, consignment contact) and per-animal counts are reset
 * so the copy starts as a fresh incomplete draft.
 *
 * <p>MapStruct was not used because the {@link CommodityComplement} transformation —
 * retaining only {@code typeOfCommodity} while explicitly nulling all per-animal data —
 * requires a custom element-level mapping method. Encoding the retain/reset rules
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
            .placeOfOrigin(mapParty(source.getPlaceOfOrigin()))
            .consignor(mapParty(source.getConsignor()))
            .consignee(mapParty(source.getConsignee()))
            .importer(mapParty(source.getImporter()))
            .destination(mapParty(source.getDestination()))
            .cphNumber(source.getCphNumber())
            // transport intentionally omitted — logistical fields (portOfEntry, arrivalDate, transporter) are reset on copy
            // consignment intentionally omitted — contact address is reset on copy
            .build();
    }

    /**
     * Carries a party onto the copy. A referenced party copies as the reference alone — the copy
     * resolves it on read like any other, so it tracks later edits rather than freezing whatever the
     * source happened to show. An inline party copies its details across.
     */
    private ConsignmentParty mapParty(ConsignmentParty source) {
        if (source == null) {
            return null;
        }
        return ConsignmentParty.builder()
            .addressId(source.getAddressId())
            .name(source.getName())
            .email(source.getEmail())
            .phone(source.getPhone())
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
            // internalReference intentionally omitted — per-consignment reference is reset on copy
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
        // totalNoOfAnimals, totalNoOfPackages, species intentionally omitted — per-animal counts are reset on copy; only typeOfCommodity is retained
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
            // unweanedAnimals intentionally omitted — animal-specific detail is reset on copy
            .build();
    }
}
