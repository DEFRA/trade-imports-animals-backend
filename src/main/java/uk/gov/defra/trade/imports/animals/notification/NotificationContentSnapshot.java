package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Point-in-time copy of amendable notification content, captured when a trader
 * starts an amendment so it can be restored if the amendment is cancelled.
 */
@Value
@Builder
public class NotificationContentSnapshot {

    Origin origin;
    Commodity commodity;
    String reasonForImport;
    AdditionalDetails additionalDetails;
    Operator placeOfOrigin;
    Operator consignor;
    Operator consignee;
    Operator importer;
    Operator destination;
    Operator consignment;
    String cphNumber;
    Transport transport;

    static NotificationContentSnapshot from(Notification notification) {
        return NotificationSnapshotMapper.capture(notification);
    }

    void applyTo(Notification notification) {
        NotificationSnapshotMapper.restore(this, notification);
    }
}

/**
 * Deep-copies amendable notification fields for amend cancel/restore.
 */
final class NotificationSnapshotMapper {

    private NotificationSnapshotMapper() {}

    static NotificationContentSnapshot capture(Notification source) {
        return NotificationContentSnapshot.builder()
            .origin(copyOrigin(source.getOrigin()))
            .commodity(copyCommodity(source.getCommodity()))
            .reasonForImport(source.getReasonForImport())
            .additionalDetails(copyAdditionalDetails(source.getAdditionalDetails()))
            .placeOfOrigin(copyOperator(source.getPlaceOfOrigin()))
            .consignor(copyOperator(source.getConsignor()))
            .consignee(copyOperator(source.getConsignee()))
            .importer(copyOperator(source.getImporter()))
            .destination(copyOperator(source.getDestination()))
            .consignment(copyOperator(source.getConsignment()))
            .cphNumber(source.getCphNumber())
            .transport(copyTransport(source.getTransport()))
            .build();
    }

    static void restore(NotificationContentSnapshot snapshot, Notification target) {
        target.setOrigin(copyOrigin(snapshot.getOrigin()));
        target.setCommodity(copyCommodity(snapshot.getCommodity()));
        target.setReasonForImport(snapshot.getReasonForImport());
        target.setAdditionalDetails(copyAdditionalDetails(snapshot.getAdditionalDetails()));
        target.setPlaceOfOrigin(copyOperator(snapshot.getPlaceOfOrigin()));
        target.setConsignor(copyOperator(snapshot.getConsignor()));
        target.setConsignee(copyOperator(snapshot.getConsignee()));
        target.setImporter(copyOperator(snapshot.getImporter()));
        target.setDestination(copyOperator(snapshot.getDestination()));
        target.setConsignment(copyOperator(snapshot.getConsignment()));
        target.setCphNumber(snapshot.getCphNumber());
        target.setTransport(copyTransport(snapshot.getTransport()));
    }

    private static Operator copyOperator(Operator source) {
        if (source == null) {
            return null;
        }
        return Operator.builder()
            .name(source.getName())
            .address(copyAddress(source.getAddress()))
            .build();
    }

    private static Address copyAddress(Address source) {
        if (source == null) {
            return null;
        }
        return Address.builder()
            .addressLine1(source.getAddressLine1())
            .addressLine2(source.getAddressLine2())
            .addressLine3(source.getAddressLine3())
            .city(source.getCity())
            .country(source.getCountry())
            .build();
    }

    private static Origin copyOrigin(Origin source) {
        if (source == null) {
            return null;
        }
        return Origin.builder()
            .countryCode(source.getCountryCode())
            .requiresRegionCode(source.getRequiresRegionCode())
            .internalReference(source.getInternalReference())
            .build();
    }

    private static Commodity copyCommodity(Commodity source) {
        if (source == null) {
            return null;
        }
        return Commodity.builder()
            .name(source.getName())
            .commodityComplement(copyComplements(source.getCommodityComplement()))
            .build();
    }

    private static List<CommodityComplement> copyComplements(List<CommodityComplement> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream()
            .map(cc -> CommodityComplement.builder()
                .typeOfCommodity(cc.getTypeOfCommodity())
                .species(cc.getSpecies())
                .totalNoOfAnimals(cc.getTotalNoOfAnimals())
                .totalNoOfPackages(cc.getTotalNoOfPackages())
                .build())
            .toList();
    }

    private static AdditionalDetails copyAdditionalDetails(AdditionalDetails source) {
        if (source == null) {
            return null;
        }
        return AdditionalDetails.builder()
            .certifiedFor(source.getCertifiedFor())
            .unweanedAnimals(source.getUnweanedAnimals())
            .build();
    }

    private static Transport copyTransport(Transport source) {
        if (source == null) {
            return null;
        }
        Transporter transporter = source.getTransporter();
        return Transport.builder()
            .portOfEntry(source.getPortOfEntry())
            .arrivalDate(source.getArrivalDate())
            .transporter(transporter == null ? null : Transporter.builder()
                .name(transporter.getName())
                .address(copyAddress(transporter.getAddress()))
                .approvalNumber(transporter.getApprovalNumber())
                .type(transporter.getType())
                .build())
            .build();
    }
}
