package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignees;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.importers;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.placesOfOrigin;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.species;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NotificationCopyMapperTest {

    private NotificationCopyMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new NotificationCopyMapper();
    }

    @Nested
    class RetainedFields {

        @Test
        void toCopyDto_shouldRetainCountryOfOriginAndRequiresRegionCode() {
            Notification source = Notification.builder()
                .origin(new Origin("DE", "yes", "INTERNAL-REF"))
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(result.getOrigin().getRequiresRegionCode()).isEqualTo("yes");
        }

        @Test
        void toCopyDto_shouldRetainReasonForImport() {
            Notification source = Notification.builder()
                .reasonForImport("internalMarket")
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getReasonForImport()).isEqualTo("internalMarket");
        }

        @Test
        void toCopyDto_shouldRetainCommodityNameAndTypeOfCommodity() {
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            Notification source = Notification.builder()
                .commodity(Commodity.builder()
                    .name("Live bovine animals")
                    .commodityComplement(List.of(complement))
                    .build())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(result.getCommodity().getCommodityComplement()).hasSize(1);
            assertThat(result.getCommodity().getCommodityComplement().getFirst().getTypeOfCommodity())
                .isEqualTo("LIVE");
        }

        @Test
        void toCopyDto_shouldRetainCertifiedFor() {
            Notification source = Notification.builder()
                .additionalDetails(new AdditionalDetails("Breeding", "yes"))
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
        }

        @Test
        void toCopyDto_shouldRetainPlaceOfOrigin() {
            Notification source = Notification.builder()
                .placeOfOrigin(placesOfOrigin().getFirst())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getPlaceOfOrigin()).isEqualTo(placesOfOrigin().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainConsignor() {
            Notification source = Notification.builder()
                .consignor(consignors().getFirst())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getConsignor()).isEqualTo(consignors().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainConsignee() {
            Notification source = Notification.builder()
                .consignee(consignees().getFirst())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getConsignee()).isEqualTo(consignees().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainImporter() {
            Notification source = Notification.builder()
                .importer(importers().getFirst())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getImporter()).isEqualTo(importers().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainDestination() {
            Notification source = Notification.builder()
                .destination(destinations().getFirst())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getDestination()).isEqualTo(destinations().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainCphNumber() {
            Notification source = Notification.builder()
                .cphNumber("12/345/6789")
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getCphNumber()).isEqualTo("12/345/6789");
        }
    }

    @Nested
    class ExcludedFields {

        @Test
        void toCopyDto_shouldOmitInternalReference() {
            Notification source = Notification.builder()
                .origin(new Origin("FR", "no", "DO-NOT-COPY"))
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getOrigin().getInternalReference()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitPerAnimalDataFromCommodityComplement() {
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            Notification source = Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(complement))
                    .build())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            CommodityComplement copied = result.getCommodity().getCommodityComplement().getFirst();
            assertThat(copied.getTotalNoOfAnimals()).isNull();
            assertThat(copied.getTotalNoOfPackages()).isNull();
            assertThat(copied.getSpecies()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitPerAnimalDataFromAllComplements() {
            List<CommodityComplement> complements = List.of(
                new CommodityComplement("LIVE", 3, 1, List.of(species())),
                new CommodityComplement("GERM", 7, 2, List.of(species()))
            );
            Notification source = Notification.builder()
                .commodity(Commodity.builder().commodityComplement(complements).build())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            result.getCommodity().getCommodityComplement().forEach(cc -> {
                assertThat(cc.getTotalNoOfAnimals()).isNull();
                assertThat(cc.getTotalNoOfPackages()).isNull();
                assertThat(cc.getSpecies()).isNull();
            });
        }

        @Test
        void toCopyDto_shouldOmitUnweanedAnimals() {
            Notification source = Notification.builder()
                .additionalDetails(new AdditionalDetails("Breeding", "yes"))
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getAdditionalDetails().getUnweanedAnimals()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitTransport() {
            Notification source = Notification.builder()
                .transport(Transport.builder()
                    .portOfEntry("GBDVR")
                    .arrivalDate(LocalDate.of(2026, 6, 1))
                    .build())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getTransport()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitConsignment() {
            Notification source = Notification.builder()
                .consignment(consignments().getFirst())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getConsignment()).isNull();
        }
    }

    @Nested
    class NullSafety {

        @Test
        void toCopyDto_shouldHandleNullOrigin() {
            Notification source = Notification.builder().origin(null).build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getOrigin()).isNull();
        }

        @Test
        void toCopyDto_shouldHandleNullCommodity() {
            Notification source = Notification.builder().commodity(null).build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getCommodity()).isNull();
        }

        @Test
        void toCopyDto_shouldHandleNullCommodityComplementList() {
            Notification source = Notification.builder()
                .commodity(Commodity.builder().name("Cattle").commodityComplement(null).build())
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getCommodity().getCommodityComplement()).isNullOrEmpty();
        }

        @Test
        void toCopyDto_shouldHandleNullAdditionalDetails() {
            Notification source = Notification.builder().additionalDetails(null).build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getAdditionalDetails()).isNull();
        }

        @Test
        void toCopyDto_shouldHandleNullOperatorFields() {
            Notification source = Notification.builder()
                .placeOfOrigin(null)
                .consignor(null)
                .consignee(null)
                .importer(null)
                .destination(null)
                .build();

            NotificationDto result = mapper.toCopyDto(source);

            assertThat(result.getPlaceOfOrigin()).isNull();
            assertThat(result.getConsignor()).isNull();
            assertThat(result.getConsignee()).isNull();
            assertThat(result.getImporter()).isNull();
            assertThat(result.getDestination()).isNull();
        }
    }
}
