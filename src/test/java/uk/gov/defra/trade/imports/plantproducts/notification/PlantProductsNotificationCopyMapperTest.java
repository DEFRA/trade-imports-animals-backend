package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedNotification;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlantProductsNotificationCopyMapperTest {

    private PlantProductsNotificationCopyMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PlantProductsNotificationCopyMapper();
    }

    @Test
    void copyFrom_shouldApplyRetainAndResetTable() {
        // Given
        PlantProductsNotification source = fullyPopulatedNotification();
        source.setSubmittedBaseline(PlantProductsNotificationContentSnapshot.from(source));
        LocalDateTime testStart = LocalDateTime.now();

        // When
        PlantProductsNotification copy = mapper.copyFrom(source);

        // Then — reset fields
        assertThat(copy.getId()).isNull();
        assertThat(copy.getReferenceNumber()).isNull();
        assertThat(copy.getStatus()).isEqualTo(PlantProductsNotificationStatus.DRAFT);
        assertThat(copy.getDeclaration()).isNull();
        assertThat(copy.getSubmittedBaseline()).isNull();
        assertThat(copy.getExpireAt()).isNull();
        assertThat(copy.getCreated()).isAfterOrEqualTo(testStart);
        assertThat(copy.getUpdated()).isAfterOrEqualTo(testStart);

        // And — retain every content group except declaration, plus ownership
        assertThat(copy.getOrigin()).isEqualTo(source.getOrigin());
        assertThat(copy.getReasonForImport()).isEqualTo(source.getReasonForImport());
        assertThat(copy.getCommodity()).isEqualTo(source.getCommodity());
        assertThat(copy.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
            .getVarieties().getFirst().getVarietyClass()).isEqualTo(VarietyClass.CLASS_I);
        assertThat(copy.getAdditionalDetails()).isEqualTo(source.getAdditionalDetails());
        assertThat(copy.getConsignor()).isEqualTo(source.getConsignor());
        assertThat(copy.getConsignee()).isEqualTo(source.getConsignee());
        assertThat(copy.getImporter()).isEqualTo(source.getImporter());
        assertThat(copy.getDestination()).isEqualTo(source.getDestination());
        assertThat(copy.getPacker()).isEqualTo(source.getPacker());
        assertThat(copy.getResponsiblePerson()).isEqualTo(source.getResponsiblePerson());
        assertThat(copy.getNominatedContacts()).isEqualTo(source.getNominatedContacts());
        assertThat(copy.getTransport()).isEqualTo(source.getTransport());
        assertThat(copy.getGoodsMovementServices()).isEqualTo(source.getGoodsMovementServices());
        assertThat(copy.getIsCuc()).isEqualTo(source.getIsCuc());
        assertThat(copy.getBilling()).isEqualTo(source.getBilling());
        assertThat(copy.getOwnership()).isEqualTo(source.getOwnership());
        assertThat(copy.getOwnership()).isNotSameAs(source.getOwnership());
    }

    @Test
    void copyFrom_shouldDeepCopyNestedSpeciesGraph() {
        // Given
        PlantProductsNotification source = fullyPopulatedNotification();
        PlantProductsNotification copy = mapper.copyFrom(source);

        // When
        source.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
            .getVarieties().getFirst().setVariety("Changed after copy");

        // Then
        assertThat(copy.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
            .getVarieties().getFirst().getVariety()).isEqualTo("Maris Piper");
    }
}
