package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
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
            // Given
            Notification source = Notification.builder()
                .origin(new Origin("DE", "yes", "INTERNAL-REF"))
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getOrigin().getCountryCode()).isEqualTo("DE");
            assertThat(result.getOrigin().getRequiresRegionCode()).isEqualTo("yes");
        }

        @Test
        void toCopyDto_shouldRetainReasonForImport() {
            // Given
            Notification source = Notification.builder()
                .reasonForImport("internalMarket")
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getReasonForImport()).isEqualTo("internalMarket");
        }

        @Test
        void toCopyDto_shouldRetainCommodityNameAndTypeOfCommodity() {
            // Given
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            Notification source = Notification.builder()
                .commodity(Commodity.builder()
                    .name("Live bovine animals")
                    .commodityComplement(List.of(complement))
                    .build())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getCommodity().getName()).isEqualTo("Live bovine animals");
            assertThat(result.getCommodity().getCommodityComplement()).hasSize(1);
            assertThat(result.getCommodity().getCommodityComplement().getFirst().getTypeOfCommodity())
                .isEqualTo("LIVE");
        }

        @Test
        void toCopyDto_shouldRetainCertifiedFor() {
            // Given
            Notification source = Notification.builder()
                .additionalDetails(new AdditionalDetails("Breeding", "yes"))
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
        }

        @Test
        void toCopyDto_shouldRetainConsignor() {
            // Given
            Notification source = Notification.builder()
                .consignor(consignors().getFirst())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getConsignor()).isEqualTo(consignors().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainDestination() {
            // Given
            Notification source = Notification.builder()
                .destination(destinations().getFirst())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getDestination()).isEqualTo(destinations().getFirst());
        }

        @Test
        void toCopyDto_shouldRetainCphNumber() {
            // Given
            Notification source = Notification.builder()
                .cphNumber("12/345/6789")
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getCphNumber()).isEqualTo("12/345/6789");
        }
    }

    @Nested
    class ExcludedFields {

        @Test
        void toCopyDto_shouldOmitInternalReference() {
            // Given
            Notification source = Notification.builder()
                .origin(new Origin("FR", "no", "DO-NOT-COPY"))
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getOrigin().getInternalReference()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitPerAnimalDataFromCommodityComplement() {
            // Given
            CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species()));
            Notification source = Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(complement))
                    .build())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            CommodityComplement copied = result.getCommodity().getCommodityComplement().getFirst();
            assertThat(copied.getTotalNoOfAnimals()).isNull();
            assertThat(copied.getTotalNoOfPackages()).isNull();
            assertThat(copied.getSpecies()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitPerAnimalDataFromAllComplements() {
            // Given
            List<CommodityComplement> complements = List.of(
                new CommodityComplement("LIVE", 3, 1, List.of(species())),
                new CommodityComplement("GERM", 7, 2, List.of(species()))
            );
            Notification source = Notification.builder()
                .commodity(Commodity.builder().commodityComplement(complements).build())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            result.getCommodity().getCommodityComplement().forEach(cc -> {
                assertThat(cc.getTotalNoOfAnimals()).isNull();
                assertThat(cc.getTotalNoOfPackages()).isNull();
                assertThat(cc.getSpecies()).isNull();
            });
        }

        @Test
        void toCopyDto_shouldOmitUnweanedAnimals() {
            // Given
            Notification source = Notification.builder()
                .additionalDetails(new AdditionalDetails("Breeding", "yes"))
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getAdditionalDetails().getUnweanedAnimals()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitTransport() {
            // Given
            Notification source = Notification.builder()
                .transport(Transport.builder()
                    .portOfEntry("GBDVR")
                    .arrivalDate(LocalDate.of(2026, 6, 1))
                    .build())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getTransport()).isNull();
        }

        @Test
        void toCopyDto_shouldOmitConsignment() {
            // Given
            Notification source = Notification.builder()
                .consignment(new Consignment())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getConsignment()).isNull();
        }
    }

    @Nested
    class NullSafety {

        @Test
        void toCopyDto_shouldHandleNullOrigin() {
            // Given
            Notification source = Notification.builder().origin(null).build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getOrigin()).isNull();
        }

        @Test
        void toCopyDto_shouldHandleNullCommodity() {
            // Given
            Notification source = Notification.builder().commodity(null).build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getCommodity()).isNull();
        }

        @Test
        void toCopyDto_shouldHandleNullCommodityComplementList() {
            // Given
            Notification source = Notification.builder()
                .commodity(Commodity.builder().name("Cattle").commodityComplement(null).build())
                .build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getCommodity().getCommodityComplement()).isNull();
        }

        @Test
        void toCopyDto_shouldHandleNullAdditionalDetails() {
            // Given
            Notification source = Notification.builder().additionalDetails(null).build();

            // When
            NotificationDto result = mapper.toCopyDto(source);

            // Then
            assertThat(result.getAdditionalDetails()).isNull();
        }
    }
}
