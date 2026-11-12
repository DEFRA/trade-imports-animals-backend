package uk.gov.defra.trade.imports.animals.notification;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps a {@link NotificationAggregate} to a {@link NotificationDto} for copy-as-new.
 * Keeps origin, commodity identity (name, commodity code, description, type), reason for import,
 * certified-for purpose, party addresses, CPH, internal-market fields, port of exit, and fulfilments.
 * Resets per-animal counts and species, transport, consignment contact, exit date, and internal reference.
 */
@Component
public class NotificationCopyMapper {

    public NotificationDto toCopyDto(NotificationAggregate notificationAggregate) {
        Notification source = notificationAggregate.requireNotification();
        return NotificationDto.builder()
            .origin(mapOrigin(source.getOrigin()))
            .commodities(mapCommoditiesForCopy(source.getCommodities()))
            .reasonForImport(source.getReasonForImport())
            .additionalDetails(mapAdditionalDetails(source.getAdditionalDetails()))
            .placeOfOrigin(mapConsignmentParty(source.getPlaceOfOrigin()))
            .consignor(mapConsignmentParty(source.getConsignor()))
            .consignee(mapConsignmentParty(source.getConsignee()))
            .importer(mapConsignmentParty(source.getImporter()))
            .destination(mapConsignmentParty(source.getDestination()))
            .cphNumber(source.getCphNumber())
            .purposeInInternalMarket(source.getPurposeInInternalMarket())
            .destinationCountry(source.getDestinationCountry())
            .portOfExit(source.getPortOfExit())
            .fulfilments(notificationAggregate.getFulfilments() == null ? null : new ArrayList<>(notificationAggregate.getFulfilments()))
            .build();
    }

    private ConsignmentParty mapConsignmentParty(ConsignmentParty source) {
        if (source == null) {
            return null;
        }
        return ConsignmentParty.builder()
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
            .countrySubdivisionCode(source.getCountrySubdivisionCode())
            .requiresRegionCode(source.getRequiresRegionCode())
            .regionOfOriginCode(source.getRegionOfOriginCode())
            .build();
    }

    private List<Commodity> mapCommoditiesForCopy(List<Commodity> source) {
        return source.stream()
            .map(entry -> Commodity.builder()
                .name(entry.getName())
                .commodityCode(entry.getCommodityCode())
                .description(entry.getDescription())
                .build())
            .toList();
    }

    private AdditionalDetails mapAdditionalDetails(AdditionalDetails source) {
        if (source == null) {
            return null;
        }
        return AdditionalDetails.builder()
            .certifiedFor(source.getCertifiedFor())
            .build();
    }
}
