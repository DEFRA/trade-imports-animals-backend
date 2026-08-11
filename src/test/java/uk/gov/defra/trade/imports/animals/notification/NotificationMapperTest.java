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
    void toResponse_shouldMapAllViewFieldsToResponse() {
        NotificationView view = view()
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

        NotificationResponse response = mapper.toResponse(view);

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
        NotificationResponse response = mapper.toResponse(view().build());

        assertThat(response.accompanyingDocuments()).isNull();
    }

    @Test
    void toResponse_shouldHandleNullFieldsGracefully() {
        NotificationView view = view()
            .referenceNumber("IMP.GB.2026.0000001")
            .build();

        NotificationResponse response = mapper.toResponse(view);

        assertThat(response.referenceNumber()).isEqualTo("IMP.GB.2026.0000001");
        assertThat(response.placeOfOrigin()).isNull();
        assertThat(response.consignor()).isNull();
        assertThat(response.consignee()).isNull();
        assertThat(response.importer()).isNull();
        assertThat(response.destination()).isNull();
        assertThat(response.consignment()).isNull();
        assertThat(response.origin()).isNull();
    }

    private static ViewBuilder view() {
        return new ViewBuilder();
    }

    private static final class ViewBuilder {
        private String id;
        private String referenceNumber;
        private NotificationStatus status;
        private LocalDateTime created;
        private LocalDateTime updated;
        private Origin origin;
        private Commodity commodity;
        private String reasonForImport;
        private AdditionalDetails additionalDetails;
        private Operator placeOfOrigin;
        private Operator consignor;
        private Operator consignee;
        private Operator importer;
        private Operator destination;
        private Operator consignment;
        private String cphNumber;
        private Transport transport;

        ViewBuilder id(String v) { this.id = v; return this; }
        ViewBuilder referenceNumber(String v) { this.referenceNumber = v; return this; }
        ViewBuilder status(NotificationStatus v) { this.status = v; return this; }
        ViewBuilder created(LocalDateTime v) { this.created = v; return this; }
        ViewBuilder updated(LocalDateTime v) { this.updated = v; return this; }
        ViewBuilder origin(Origin v) { this.origin = v; return this; }
        ViewBuilder commodity(Commodity v) { this.commodity = v; return this; }
        ViewBuilder reasonForImport(String v) { this.reasonForImport = v; return this; }
        ViewBuilder additionalDetails(AdditionalDetails v) { this.additionalDetails = v; return this; }
        ViewBuilder placeOfOrigin(Operator v) { this.placeOfOrigin = v; return this; }
        ViewBuilder consignor(Operator v) { this.consignor = v; return this; }
        ViewBuilder consignee(Operator v) { this.consignee = v; return this; }
        ViewBuilder importer(Operator v) { this.importer = v; return this; }
        ViewBuilder destination(Operator v) { this.destination = v; return this; }
        ViewBuilder consignment(Operator v) { this.consignment = v; return this; }
        ViewBuilder cphNumber(String v) { this.cphNumber = v; return this; }
        ViewBuilder transport(Transport v) { this.transport = v; return this; }

        NotificationView build() {
            return new NotificationView() {
                @Override public String getId() { return id; }
                @Override public String getReferenceNumber() { return referenceNumber; }
                @Override public NotificationStatus getStatus() { return status; }
                @Override public LocalDateTime getCreated() { return created; }
                @Override public LocalDateTime getUpdated() { return updated; }
                @Override public Origin getOrigin() { return origin; }
                @Override public Commodity getCommodity() { return commodity; }
                @Override public String getReasonForImport() { return reasonForImport; }
                @Override public AdditionalDetails getAdditionalDetails() { return additionalDetails; }
                @Override public Operator getPlaceOfOrigin() { return placeOfOrigin; }
                @Override public Operator getConsignor() { return consignor; }
                @Override public Operator getConsignee() { return consignee; }
                @Override public Operator getImporter() { return importer; }
                @Override public Operator getDestination() { return destination; }
                @Override public Operator getConsignment() { return consignment; }
                @Override public String getCphNumber() { return cphNumber; }
                @Override public Transport getTransport() { return transport; }
            };
        }
    }
}
