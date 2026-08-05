package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedDto;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedNotification;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PlantProductsNotificationMapperTest {

    private final PlantProductsNotificationMapper mapper =
        Mappers.getMapper(PlantProductsNotificationMapper.class);

    @Nested
    class ApplyContent {

        @Test
        void applyContent_shouldCopyEveryContentGroup() {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            PlantProductsNotification target = new PlantProductsNotification();

            // When
            mapper.applyContent(dto, target);

            // Then
            assertContentMatches(target, dto);
        }

        @Test
        void applyContent_shouldNeverOverwriteServerSetFields() {
            // Given
            LocalDateTime created = LocalDateTime.of(2026, 1, 2, 3, 4);
            LocalDateTime updated = created.plusHours(1);
            LocalDateTime expiry = created.plusDays(30);
            Ownership ownership = Ownership.builder()
                .assignedOrganisationId("server-org")
                .assignedOrganisationName("Server organisation")
                .build();
            PlantProductsNotification baselineSource = fullyPopulatedNotification();
            PlantProductsNotificationContentSnapshot baseline =
                PlantProductsNotificationContentSnapshot.from(baselineSource);
            PlantProductsNotification target = PlantProductsNotification.builder()
                .id("server-id")
                .referenceNumber("GBN-PP-26-SRV001")
                .chedType("CHEDPP")
                .status(PlantProductsNotificationStatus.AMEND)
                .ownership(ownership)
                .created(created)
                .updated(updated)
                .submittedBaseline(baseline)
                .expireAt(expiry)
                .build();
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber("GBN-PP-26-DT0001");
            dto.setChedType("CONFLICT");
            dto.setStatus(PlantProductsNotificationStatus.DELETED);
            dto.setOwnership(Ownership.builder().assignedOrganisationId("dto-org").build());
            dto.setCreated(created.minusDays(1));
            dto.setUpdated(updated.minusDays(1));

            // When
            mapper.applyContent(dto, target);

            // Then
            assertThat(target.getId()).isEqualTo("server-id");
            assertThat(target.getReferenceNumber()).isEqualTo("GBN-PP-26-SRV001");
            assertThat(target.getChedType()).isEqualTo("CHEDPP");
            assertThat(target.getStatus()).isEqualTo(PlantProductsNotificationStatus.AMEND);
            assertThat(target.getOwnership()).isSameAs(ownership);
            assertThat(target.getCreated()).isEqualTo(created);
            assertThat(target.getUpdated()).isEqualTo(updated);
            assertThat(target.getSubmittedBaseline()).isSameAs(baseline);
            assertThat(target.getExpireAt()).isEqualTo(expiry);
        }

        @Test
        void applyContent_shouldHandleNullNestedObjectsAndLists() {
            // Given
            PlantProductsNotificationDto dto = PlantProductsNotificationDto.builder()
                .commodity(null)
                .nominatedContacts(null)
                .transport(null)
                .build();

            // When
            mapper.applyContent(dto, new PlantProductsNotification());

            // Then — reaching here proves the null graph is accepted without an NPE
            assertThat(dto.getCommodity()).isNull();
        }
    }

    @Nested
    class ToDto {

        @Test
        void toDto_shouldMapContentAndVisibleServerFields() {
            // Given
            PlantProductsNotification source = fullyPopulatedNotification();
            source.setSubmittedBaseline(PlantProductsNotificationContentSnapshot.from(source));

            // When
            PlantProductsNotificationDto result = mapper.toDto(source);

            // Then
            assertContentMatches(result, source);
            assertThat(result.getReferenceNumber()).isEqualTo(source.getReferenceNumber());
            assertThat(result.getChedType()).isEqualTo("CHEDPP");
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.SUBMITTED);
            assertThat(result.getCreated()).isEqualTo(source.getCreated());
            assertThat(result.getUpdated()).isEqualTo(source.getUpdated());
        }

        @Test
        void toDto_shouldHandleNullNestedObjectsAndLists() {
            // Given
            PlantProductsNotification source = PlantProductsNotification.builder()
                .nominatedContacts(null)
                .commodity(null)
                .transport(null)
                .build();

            // When
            PlantProductsNotificationDto result = mapper.toDto(source);

            // Then
            assertThat(result.getNominatedContacts()).isNull();
            assertThat(result.getCommodity()).isNull();
            assertThat(result.getTransport()).isNull();
        }
    }

    private static void assertContentMatches(
        PlantProductsNotificationBase actual, PlantProductsNotificationBase expected) {
        assertThat(actual.getOrigin().getCountryCode()).isEqualTo(expected.getOrigin().getCountryCode());
        assertThat(actual.getReasonForImport()).isEqualTo(expected.getReasonForImport());
        assertThat(actual.getCommodity().getInputMethod()).isEqualTo(expected.getCommodity().getInputMethod());
        assertThat(actual.getCommodity().getCommodityComplement().getFirst().getQuantity())
            .isEqualByComparingTo(expected.getCommodity().getCommodityComplement().getFirst().getQuantity());
        assertThat(actual.getCommodity().getCommodityComplement().getFirst().getNetWeight())
            .isEqualByComparingTo(expected.getCommodity().getCommodityComplement().getFirst().getNetWeight());
        assertThat(actual.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst()
            .getVarieties().getFirst().getVarietyClass()).isEqualTo(VarietyClass.CLASS_I);
        assertThat(actual.getAdditionalDetails().getTotalGrossWeight())
            .isEqualByComparingTo(expected.getAdditionalDetails().getTotalGrossWeight());
        assertThat(actual.getConsignor().getAddress().getCity()).isEqualTo(expected.getConsignor().getAddress().getCity());
        assertThat(actual.getConsignee().getName()).isEqualTo(expected.getConsignee().getName());
        assertThat(actual.getImporter().getName()).isEqualTo(expected.getImporter().getName());
        assertThat(actual.getDestination().getName()).isEqualTo(expected.getDestination().getName());
        assertThat(actual.getPacker().getName()).isEqualTo(expected.getPacker().getName());
        assertThat(actual.getResponsiblePerson().getEmail()).isEqualTo(expected.getResponsiblePerson().getEmail());
        assertThat(actual.getNominatedContacts()).hasSize(1);
        assertThat(actual.getTransport().getContainers()).hasSize(1);
        assertThat(actual.getGoodsMovementServices().getMovementReferenceNumber())
            .isEqualTo(expected.getGoodsMovementServices().getMovementReferenceNumber());
        assertThat(actual.getIsCuc()).isFalse();
        assertThat(actual.getBilling().getAddress().getPostalCode())
            .isEqualTo(expected.getBilling().getAddress().getPostalCode());
        assertThat(actual.getDeclaration().getAgreed()).isTrue();
    }
}
