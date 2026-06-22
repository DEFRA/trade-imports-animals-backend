package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignees;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignments;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.consignors;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.destinations;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.importers;
import static uk.gov.defra.trade.imports.animals.utils.NotificationTestData.placesOfOrigin;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class NotificationMapperTest {

    private final NotificationMapper mapper = Mappers.getMapper(NotificationMapper.class);

    @Test
    void toResponse_shouldMapAllEntityFieldsToResponse() {
        Notification notification = Notification.builder()
            .id("notif-id-001")
            .referenceNumber("IMP.GB.2026.1001401")
            .origin(Origin.builder()
                .countryCode("FI")
                .requiresRegionCode("no")
                .internalReference("FIN-EXP-2026.449B")
                .build())
            .commodity(Commodity.builder()
                .name("Cow")
                .build())
            .reasonForImport("internalMarket")
            .additionalDetails(AdditionalDetails.builder()
                .certifiedFor("Breeding")
                .unweanedAnimals("No")
                .build())
            .placeOfOrigin(placesOfOrigin().getFirst())
            .consignor(consignors().getFirst())
            .consignee(consignees().getFirst())
            .importer(importers().getFirst())
            .destination(destinations().getFirst())
            .consignment(consignments().getFirst())
            .cphNumber("12/343/R783")
            .status(NotificationStatus.DRAFT)
            .created(LocalDateTime.of(2026, 4, 15, 10, 0))
            .updated(LocalDateTime.of(2026, 4, 16, 9, 0))
            .build();

        NotificationResponse response = mapper.toResponse(notification);

        assertThat(response.id()).isEqualTo("notif-id-001");
        assertThat(response.referenceNumber()).isEqualTo("IMP.GB.2026.1001401");
        assertThat(response.origin().getCountryCode()).isEqualTo("FI");
        assertThat(response.commodity().getName()).isEqualTo("Cow");
        assertThat(response.reasonForImport()).isEqualTo("internalMarket");
        assertThat(response.additionalDetails().getCertifiedFor()).isEqualTo("Breeding");
        assertThat(response.additionalDetails().getUnweanedAnimals()).isEqualTo("No");
        assertThat(response.placeOfOrigin().getName()).isEqualTo(placesOfOrigin().getFirst().getName());
        assertThat(response.consignor().getName()).isEqualTo(consignors().getFirst().getName());
        assertThat(response.consignee().getName()).isEqualTo(consignees().getFirst().getName());
        assertThat(response.importer().getName()).isEqualTo(importers().getFirst().getName());
        assertThat(response.destination().getName()).isEqualTo(destinations().getFirst().getName());
        assertThat(response.consignment().getName()).isEqualTo(consignments().getFirst().getName());
        assertThat(response.cphNumber()).isEqualTo("12/343/R783");
        assertThat(response.status()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(response.created()).isEqualTo(LocalDateTime.of(2026, 4, 15, 10, 0));
        assertThat(response.updated()).isEqualTo(LocalDateTime.of(2026, 4, 16, 9, 0));
    }

    @Test
    void toResponse_shouldLeaveAccompanyingDocumentsNull() {
        NotificationResponse response = mapper.toResponse(Notification.builder().build());

        assertThat(response.accompanyingDocuments()).isNull();
    }

    @Test
    void toResponse_shouldHandleNullFieldsGracefully() {
        Notification notification = Notification.builder()
            .referenceNumber("IMP.GB.2026.0000001")
            .build();

        NotificationResponse response = mapper.toResponse(notification);

        assertThat(response.referenceNumber()).isEqualTo("IMP.GB.2026.0000001");
        assertThat(response.placeOfOrigin()).isNull();
        assertThat(response.consignor()).isNull();
        assertThat(response.consignee()).isNull();
        assertThat(response.importer()).isNull();
        assertThat(response.destination()).isNull();
        assertThat(response.consignment()).isNull();
        assertThat(response.origin()).isNull();
    }
}
