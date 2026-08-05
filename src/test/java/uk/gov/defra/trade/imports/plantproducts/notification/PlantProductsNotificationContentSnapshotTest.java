package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedNotification;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlantProductsNotificationContentSnapshotTest {

    @Nested
    class From {

        @Test
        void from_shouldCaptureAllSixteenAmendableContentFields() {
            // Given
            PlantProductsNotification source = fullyPopulatedNotification();

            // When
            PlantProductsNotificationContentSnapshot snapshot =
                PlantProductsNotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getOrigin()).isEqualTo(source.getOrigin());
            assertThat(snapshot.getReasonForImport()).isEqualTo(source.getReasonForImport());
            assertThat(snapshot.getCommodity()).isEqualTo(source.getCommodity());
            assertThat(snapshot.getAdditionalDetails()).isEqualTo(source.getAdditionalDetails());
            assertThat(snapshot.getConsignor()).isEqualTo(source.getConsignor());
            assertThat(snapshot.getConsignee()).isEqualTo(source.getConsignee());
            assertThat(snapshot.getImporter()).isEqualTo(source.getImporter());
            assertThat(snapshot.getDestination()).isEqualTo(source.getDestination());
            assertThat(snapshot.getPacker()).isEqualTo(source.getPacker());
            assertThat(snapshot.getResponsiblePerson()).isEqualTo(source.getResponsiblePerson());
            assertThat(snapshot.getNominatedContacts()).isEqualTo(source.getNominatedContacts());
            assertThat(snapshot.getTransport()).isEqualTo(source.getTransport());
            assertThat(snapshot.getGoodsMovementServices()).isEqualTo(source.getGoodsMovementServices());
            assertThat(snapshot.getIsCuc()).isEqualTo(source.getIsCuc());
            assertThat(snapshot.getBilling()).isEqualTo(source.getBilling());
            assertThat(snapshot.getDeclaration()).isEqualTo(source.getDeclaration());
        }

        @Test
        void from_shouldDeepCloneNestedCommodityAndContactGraphs() {
            // Given
            PlantProductsNotification source = fullyPopulatedNotification();
            PlantProductsNotificationContentSnapshot snapshot =
                PlantProductsNotificationContentSnapshot.from(source);

            // When
            source.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
                .getVarieties().getFirst().setVariety("Changed variety");
            source.getNominatedContacts().getFirst().setName("Changed contact");

            // Then
            assertThat(snapshot.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
                .getVarieties().getFirst().getVariety()).isEqualTo("Maris Piper");
            assertThat(snapshot.getNominatedContacts().getFirst().getName()).isEqualTo("Nominated Agent");
        }

        @Test
        void from_shouldNormaliseNullListsToEmptyLists() {
            // Given
            PlantProductsNotification source = fullyPopulatedNotification();
            source.getCommodity().setCommodityComplement(null);
            source.setNominatedContacts(null);
            source.getTransport().setContainers(null);

            // When
            PlantProductsNotificationContentSnapshot snapshot =
                PlantProductsNotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getCommodity().getCommodityComplement()).isEmpty();
            assertThat(snapshot.getNominatedContacts()).isEmpty();
            assertThat(snapshot.getTransport().getContainers()).isEmpty();
        }

        @Test
        void from_shouldNormaliseNullNestedCommodityListsToEmptyLists() {
            // Given
            PlantProductsNotification source = fullyPopulatedNotification();
            CommodityLine lineWithNullSpecies = CommodityLine.builder().species(null).build();
            CommodityLine lineWithNullVarieties = CommodityLine.builder()
                .species(List.of(PlantSpecies.builder().varieties(null).build()))
                .build();
            source.getCommodity().setCommodityComplement(
                List.of(lineWithNullSpecies, lineWithNullVarieties));

            // When
            PlantProductsNotificationContentSnapshot snapshot =
                PlantProductsNotificationContentSnapshot.from(source);

            // Then
            assertThat(snapshot.getCommodity().getCommodityComplement().getFirst().getSpecies()).isEmpty();
            assertThat(snapshot.getCommodity().getCommodityComplement().get(1).getSpecies()
                .getFirst().getVarieties()).isEmpty();
        }
    }

    @Nested
    class ApplyTo {

        @Test
        void applyTo_shouldRestoreContentWithoutChangingServerFields() {
            // Given
            PlantProductsNotification source = fullyPopulatedNotification();
            PlantProductsNotificationContentSnapshot snapshot =
                PlantProductsNotificationContentSnapshot.from(source);
            LocalDateTime serverTime = LocalDateTime.of(2026, 7, 1, 9, 0);
            Ownership targetOwnership = Ownership.builder().assignedOrganisationId("other-org").build();
            PlantProductsNotification oldBaselineSource = fullyPopulatedNotification();
            oldBaselineSource.getOrigin().setCountryCode("CL");
            PlantProductsNotificationContentSnapshot oldBaseline =
                PlantProductsNotificationContentSnapshot.from(oldBaselineSource);
            PlantProductsNotification target = PlantProductsNotification.builder()
                .id("target-id")
                .referenceNumber("GBN-PP-26-TGT001")
                .chedType("CHEDPP")
                .status(PlantProductsNotificationStatus.AMEND)
                .ownership(targetOwnership)
                .origin(PlantProductsOrigin.builder().countryCode("FR").build())
                .created(serverTime)
                .updated(serverTime.plusHours(1))
                .submittedBaseline(oldBaseline)
                .expireAt(serverTime.plusDays(30))
                .build();

            // When
            snapshot.applyTo(target);

            // Then
            assertThat(target.getOrigin()).isEqualTo(source.getOrigin());
            assertThat(target.getReasonForImport()).isEqualTo(source.getReasonForImport());
            assertThat(target.getCommodity()).isEqualTo(source.getCommodity());
            assertThat(target.getAdditionalDetails()).isEqualTo(source.getAdditionalDetails());
            assertThat(target.getConsignor()).isEqualTo(source.getConsignor());
            assertThat(target.getConsignee()).isEqualTo(source.getConsignee());
            assertThat(target.getImporter()).isEqualTo(source.getImporter());
            assertThat(target.getDestination()).isEqualTo(source.getDestination());
            assertThat(target.getPacker()).isEqualTo(source.getPacker());
            assertThat(target.getResponsiblePerson()).isEqualTo(source.getResponsiblePerson());
            assertThat(target.getNominatedContacts()).isEqualTo(source.getNominatedContacts());
            assertThat(target.getTransport()).isEqualTo(source.getTransport());
            assertThat(target.getGoodsMovementServices()).isEqualTo(source.getGoodsMovementServices());
            assertThat(target.getIsCuc()).isEqualTo(source.getIsCuc());
            assertThat(target.getBilling()).isEqualTo(source.getBilling());
            assertThat(target.getDeclaration()).isEqualTo(source.getDeclaration());
            assertThat(target.getId()).isEqualTo("target-id");
            assertThat(target.getReferenceNumber()).isEqualTo("GBN-PP-26-TGT001");
            assertThat(target.getChedType()).isEqualTo("CHEDPP");
            assertThat(target.getStatus()).isEqualTo(PlantProductsNotificationStatus.AMEND);
            assertThat(target.getOwnership()).isSameAs(targetOwnership);
            assertThat(target.getCreated()).isEqualTo(serverTime);
            assertThat(target.getUpdated()).isEqualTo(serverTime.plusHours(1));
            assertThat(target.getSubmittedBaseline()).isSameAs(oldBaseline);
            assertThat(target.getExpireAt()).isEqualTo(serverTime.plusDays(30));
        }
    }
}
