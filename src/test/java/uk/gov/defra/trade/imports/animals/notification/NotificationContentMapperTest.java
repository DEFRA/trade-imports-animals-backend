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
            .commodity(Commodity.builder().name("Cattle").commodityComplement(null).build())
            .build();

        Notification clone = mapper.deepClone(source);

        assertThat(clone.getCommodity().getCommodityComplement()).isNotNull().isEmpty();
    }

    @Test
    void deepClone_shouldPreservePopulatedCommodityComplement() {
        CommodityComplement complement = CommodityComplement.builder()
            .typeOfCommodity("LIVE")
            .totalNoOfAnimals(10)
            .build();
        Notification source = Notification.builder()
            .commodity(Commodity.builder()
                .name("Cattle")
                .commodityComplement(List.of(complement))
                .build())
            .build();

        Notification clone = mapper.deepClone(source);

        assertThat(clone.getCommodity().getName()).isEqualTo("Cattle");
        assertThat(clone.getCommodity().getCommodityComplement())
            .hasSize(1)
            .first()
            .satisfies(c -> {
                assertThat(c.getTypeOfCommodity()).isEqualTo("LIVE");
                assertThat(c.getTotalNoOfAnimals()).isEqualTo(10);
            });
    }

    @Test
    void deepClone_shouldProduceIndependentObjectGraph_soMutatingCloneDoesNotAffectSource() {
        CommodityComplement complement = CommodityComplement.builder().typeOfCommodity("LIVE").build();
        Notification source = Notification.builder()
            .commodity(Commodity.builder().name("Cattle").commodityComplement(List.of(complement)).build())
            .reasonForImport("PERMANENT")
            .build();

        Notification clone = mapper.deepClone(source);
        clone.setReasonForImport("MUTATED");
        clone.getCommodity().setName("MutatedCattle");

        assertThat(source.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(source.getCommodity().getName()).isEqualTo("Cattle");
    }

    @Test
    void deepClone_shouldRoundTripContentFields() {
        Notification source = Notification.builder()
            .origin(new Origin("GB", "true", "REF-1"))
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
