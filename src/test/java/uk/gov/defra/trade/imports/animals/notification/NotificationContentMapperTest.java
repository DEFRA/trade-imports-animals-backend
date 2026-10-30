package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class NotificationContentMapperTest {

    private final NotificationContentMapper mapper = Mappers.getMapper(NotificationContentMapper.class);

    @Test
    void deepClone_shouldNormaliseNullCommodityComplementToEmptyList() {
        Notification source = Notification.builder()
            .commodities(null)
            .build();

        Notification clone = mapper.deepClone(source);

        assertThat(clone.getCommodities()).isNotNull().isEmpty();
    }

    @Test
    void deepClone_shouldPreservePopulatedCommodityComplement() {
        CommodityComplement complement = CommodityComplement.builder()
            .typeOfCommodity("LIVE")
            .totalNoOfAnimals(10)
            .build();
        Notification source = Notification.builder()
            .commodities(List.of(complement))
            .build();

        Notification clone = mapper.deepClone(source);

        assertThat(clone.getCommodities())
            .hasSize(1)
            .first()
            .satisfies(c -> {
                assertThat(c.getTypeOfCommodity()).isEqualTo("LIVE");
                assertThat(c.getTotalNoOfAnimals()).isEqualTo(10);
            });
    }

    @Test
    void deepClone_shouldProduceIndependentObjectGraph_soMutatingCloneDoesNotAffectSource() {
        CommodityComplement complement = CommodityComplement.builder()
            .name("Cattle")
            .typeOfCommodity("LIVE")
            .build();
        Notification source = Notification.builder()
            .commodities(List.of(complement))
            .reasonForImport("PERMANENT")
            .build();

        Notification clone = mapper.deepClone(source);
        clone.setReasonForImport("MUTATED");
        clone.getCommodities().getFirst().setName("MutatedCattle");

        assertThat(source.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(source.getCommodities().getFirst().getName()).isEqualTo("Cattle");
    }

    @Test
    void deepClone_shouldRoundTripContentFields() {
        Notification source = Notification.builder()
            .origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("REF-1").regionOfOriginCode("GB-ENG").build())
            .reasonForImport("PERMANENT")
            .additionalDetails(new AdditionalDetails("HUMAN_CONSUMPTION", "true"))
            .cphNumber("12/345/6789")
            .build();

        Notification clone = mapper.deepClone(source);

        assertThat(clone.getOrigin()).isEqualTo(source.getOrigin());
        assertThat(clone.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(clone.getAdditionalDetails()).isEqualTo(source.getAdditionalDetails());
        assertThat(clone.getCphNumber()).isEqualTo("12/345/6789");
    }
}
