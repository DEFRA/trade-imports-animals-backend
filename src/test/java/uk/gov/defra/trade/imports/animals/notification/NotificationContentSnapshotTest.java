package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignees;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.importers;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.placesOfOrigin;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.transporters;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NotificationContentSnapshotTest {

    @Nested
    class Capture {

        @Test
        void from_shouldDeepCopyAllAmendableFields() {
            // Given
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            Notification source = fullNotification(complement);

            // When
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(snapshot.getReasonForImport()).isEqualTo("internalMarket");
            assertThat(snapshot.getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(snapshot.getCommodity().getCommodityComplement()).hasSize(1);
            assertThat(snapshot.getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
            assertThat(snapshot.getPlaceOfOrigin().getName()).isEqualTo(placesOfOrigin().getFirst().getName());
            assertThat(snapshot.getConsignor().getName()).isEqualTo(consignors().getFirst().getName());
            assertThat(snapshot.getConsignee().getName()).isEqualTo(consignees().getFirst().getName());
            assertThat(snapshot.getImporter().getName()).isEqualTo(importers().getFirst().getName());
            assertThat(snapshot.getDestination().getName()).isEqualTo(destinations().getFirst().getName());
            assertThat(snapshot.getConsignment().getName()).isEqualTo(consignments().getFirst().getName());
            assertThat(snapshot.getCphNumber()).isEqualTo("12/345/6789");
            assertThat(snapshot.getTransport().getPortOfEntry()).isEqualTo("Felixstowe");
            assertThat(snapshot.getTransport().getTransporter().getApprovalNumber())
                .isEqualTo(transporters().getFirst().getApprovalNumber());
        }

        @Test
        void from_shouldNotShareNestedReferencesWithSource() {
            // Given
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            Notification source = fullNotification(complement);
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);

            // When
            source.getOrigin().setCountryCode("FR");
            source.getCommodity().setName("Changed");
            source.getCommodity().getCommodityComplement().getFirst().setTypeOfCommodity("PRODUCT");
            source.getConsignor().setName("Changed consignor");
            source.getTransport().getTransporter().setName("Changed transporter");

            // Then
            assertThat(snapshot.getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(snapshot.getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(snapshot.getCommodity().getCommodityComplement().getFirst().getTypeOfCommodity())
                .isEqualTo("LIVE");
            assertThat(snapshot.getConsignor().getName()).isEqualTo(consignors().getFirst().getName());
            assertThat(snapshot.getTransport().getTransporter().getName())
                .isEqualTo(transporters().getFirst().getName());
        }

        @Test
        void from_shouldUseEmptyList_whenCommodityComplementIsNull() {
            // Given
            Notification source = Notification.builder()
                .commodity(Commodity.builder().name("Cattle").commodityComplement(null).build())
                .build();

            // When
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getCommodity().getCommodityComplement()).isEmpty();
        }

        @Test
        void from_shouldHandleNullNestedObjects() {
            // Given
            Notification source = Notification.builder()
                .origin(null)
                .commodity(null)
                .additionalDetails(null)
                .placeOfOrigin(null)
                .consignor(null)
                .consignee(null)
                .importer(null)
                .destination(null)
                .consignment(null)
                .transport(null)
                .build();

            // When
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getOrigin()).isNull();
            assertThat(snapshot.getCommodity()).isNull();
            assertThat(snapshot.getAdditionalDetails()).isNull();
            assertThat(snapshot.getPlaceOfOrigin()).isNull();
            assertThat(snapshot.getConsignor()).isNull();
            assertThat(snapshot.getConsignee()).isNull();
            assertThat(snapshot.getImporter()).isNull();
            assertThat(snapshot.getDestination()).isNull();
            assertThat(snapshot.getConsignment()).isNull();
            assertThat(snapshot.getTransport()).isNull();
        }

        @Test
        void from_shouldHandleTransportWithoutTransporter() {
            // Given
            Notification source = Notification.builder()
                .transport(Transport.builder()
                    .portOfEntry("Dover")
                    .arrivalDate(LocalDate.of(2026, 3, 1))
                    .transporter(null)
                    .build())
                .build();

            // When
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getTransport().getPortOfEntry()).isEqualTo("Dover");
            assertThat(snapshot.getTransport().getTransporter()).isNull();
        }

        @Test
        void from_shouldHandleOperatorWithNullAddress() {
            // Given
            Notification source = Notification.builder()
                .consignor(Operator.builder().name("No address operator").address(null).build())
                .build();

            // When
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getConsignor().getName()).isEqualTo("No address operator");
            assertThat(snapshot.getConsignor().getAddress()).isNull();
        }
    }

    @Nested
    class Restore {

        @Test
        void applyTo_shouldRestoreAllAmendableFields() {
            // Given
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(fullNotification(complement));
            Notification target = Notification.builder()
                .referenceNumber("GBN-AG-26-RESTORE")
                .build();

            // When
            snapshot.applyTo(target);

            // Then
            assertThat(target.getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(target.getReasonForImport()).isEqualTo("internalMarket");
            assertThat(target.getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(target.getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
            assertThat(target.getConsignor().getName()).isEqualTo(consignors().getFirst().getName());
            assertThat(target.getCphNumber()).isEqualTo("12/345/6789");
            assertThat(target.getTransport().getPortOfEntry()).isEqualTo("Felixstowe");
        }

        @Test
        void applyTo_shouldDeepCopyOntoTarget() {
            // Given
            Notification source = fullNotification(
                new CommodityComplement("LIVE", 10, 5, List.of(species())));
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.from(source);
            Notification target = Notification.builder().build();
            snapshot.applyTo(target);

            // When
            target.getConsignor().setName("Mutated on target");
            snapshot.applyTo(Notification.builder().build());

            // Then — re-apply from unchanged snapshot still restores original value
            Notification secondTarget = Notification.builder().build();
            snapshot.applyTo(secondTarget);
            assertThat(secondTarget.getConsignor().getName()).isEqualTo(consignors().getFirst().getName());
        }

        @Test
        void applyTo_shouldClearFields_whenSnapshotValuesAreNull() {
            // Given
            NotificationContentSnapshot snapshot = NotificationContentSnapshot.builder().build();
            Notification target = fullNotification(
                new CommodityComplement("LIVE", 10, 5, List.of(species())));

            // When
            snapshot.applyTo(target);

            // Then
            assertThat(target.getOrigin()).isNull();
            assertThat(target.getCommodity()).isNull();
            assertThat(target.getAdditionalDetails()).isNull();
            assertThat(target.getConsignor()).isNull();
            assertThat(target.getTransport()).isNull();
        }
    }

    private static Notification fullNotification(CommodityComplement complement) {
        return Notification.builder()
            .origin(new Origin("DE", "yes", "INTERNAL-REF"))
            .reasonForImport("internalMarket")
            .commodity(Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build())
            .additionalDetails(new AdditionalDetails("Breeding", "yes"))
            .placeOfOrigin(placesOfOrigin().getFirst())
            .consignor(consignors().getFirst())
            .consignee(consignees().getFirst())
            .importer(importers().getFirst())
            .destination(destinations().getFirst())
            .consignment(consignments().getFirst())
            .cphNumber("12/345/6789")
            .transport(Transport.builder()
                .portOfEntry("Felixstowe")
                .arrivalDate(LocalDate.of(2026, 6, 1))
                .transporter(transporters().getFirst())
                .build())
            .build();
    }
}
