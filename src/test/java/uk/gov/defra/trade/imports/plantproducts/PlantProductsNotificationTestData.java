package uk.gov.defra.trade.imports.plantproducts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import uk.gov.defra.trade.imports.plantproducts.notification.BillingAddress;
import uk.gov.defra.trade.imports.plantproducts.notification.CommodityInputMethod;
import uk.gov.defra.trade.imports.plantproducts.notification.CommodityLine;
import uk.gov.defra.trade.imports.plantproducts.notification.CommonTransitConvention;
import uk.gov.defra.trade.imports.plantproducts.notification.Declaration;
import uk.gov.defra.trade.imports.plantproducts.notification.FinishedOrPropagated;
import uk.gov.defra.trade.imports.plantproducts.notification.GoodsMovementServices;
import uk.gov.defra.trade.imports.plantproducts.notification.GrossVolumeUnit;
import uk.gov.defra.trade.imports.plantproducts.notification.Ownership;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsAdditionalDetails;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsAddress;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsBilling;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsCommodity;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsContact;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsMeansOfTransport;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotification;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationDto;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsOperator;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsOrigin;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsTransport;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantSpecies;
import uk.gov.defra.trade.imports.plantproducts.notification.ReasonForImport;
import uk.gov.defra.trade.imports.plantproducts.notification.SpeciesVariety;
import uk.gov.defra.trade.imports.plantproducts.notification.TransportContainer;
import uk.gov.defra.trade.imports.plantproducts.notification.VarietyClass;

public final class PlantProductsNotificationTestData {

    private PlantProductsNotificationTestData() {
    }

    public static String refNumber(String suffix) {
        if (!suffix.matches("[0-9A-HJ-KM-NP-TV-Z]{6}")) {
            throw new IllegalArgumentException("suffix must be a six-character Crockford base32 value");
        }
        return "GBN-PP-26-" + suffix;
    }

    public static PlantProductsNotificationDto fullyPopulatedDto() {
        return PlantProductsNotificationDto.builder()
            .origin(origin())
            .reasonForImport(ReasonForImport.INTERNAL_MARKET)
            .commodity(commodity())
            .additionalDetails(PlantProductsAdditionalDetails.builder()
                .totalGrossWeight(new BigDecimal("1250.50"))
                .grossVolume(new BigDecimal("3400.75"))
                .grossVolumeUnit(GrossVolumeUnit.LITRES)
                .build())
            .consignor(operator("consignor", "Brazil Plants Ltd"))
            .consignee(operator("consignee", "GB Produce Ltd"))
            .importer(operator("importer", "GB Imports Ltd"))
            .destination(operator("destination", "London Produce Market"))
            .packer(operator("packer", "Sao Paulo Packing SA"))
            .responsiblePerson(contact("Responsible Person", false))
            .nominatedContacts(List.of(contact("Nominated Agent", true)))
            .transport(PlantProductsTransport.builder()
                .borderControlPost("GBFXT1PP")
                .inspectionPremises("IP-FELIXSTOWE-01")
                .meansOfTransport(PlantProductsMeansOfTransport.VESSEL)
                .transportIdentification("MV AMAZON STAR")
                .transportDocumentReference("BOL-2026-00421")
                .arrivalDate(LocalDate.of(2026, 9, 18))
                .arrivalTime("14:30")
                .usesContainers(true)
                .containers(List.of(TransportContainer.builder()
                    .containerNumber("MSCU1234567")
                    .sealNumber("BR998877")
                    .officialSeal(true)
                    .build()))
                .build())
            .goodsMovementServices(GoodsMovementServices.builder()
                .commonTransitConvention(CommonTransitConvention.ADD_MRN_NOW)
                .movementReferenceNumber("26GB12345678901234")
                .usingGvms(true)
                .build())
            .isCuc(false)
            .billing(PlantProductsBilling.builder()
                .address(BillingAddress.builder()
                    .addressLine1("1 Billing Street")
                    .addressLine2("Suite 2")
                    .addressLine3("Commercial Quarter")
                    .addressLine4("Greater London")
                    .cityOrTown("London")
                    .county("London")
                    .postalCode("SW1A 1AA")
                    .build())
                .email("billing@example.gov.uk")
                .telephone("+44 20 7946 0958")
                .build())
            .declaration(Declaration.builder()
                .agreed(true)
                .declaredAt(LocalDateTime.of(2026, 8, 1, 12, 0))
                .build())
            .build();
    }

    public static PlantProductsNotification fullyPopulatedNotification() {
        PlantProductsNotificationDto dto = fullyPopulatedDto();
        return PlantProductsNotification.builder()
            .id("notification-id")
            .referenceNumber(refNumber("ABC001"))
            .chedType("CHEDPP")
            .status(PlantProductsNotificationStatus.SUBMITTED)
            .ownership(Ownership.builder()
                .assignedOrganisationId("stub-org")
                .assignedOrganisationName("Stubbed organisation")
                .build())
            .origin(dto.getOrigin())
            .reasonForImport(dto.getReasonForImport())
            .commodity(dto.getCommodity())
            .additionalDetails(dto.getAdditionalDetails())
            .consignor(dto.getConsignor())
            .consignee(dto.getConsignee())
            .importer(dto.getImporter())
            .destination(dto.getDestination())
            .packer(dto.getPacker())
            .responsiblePerson(dto.getResponsiblePerson())
            .nominatedContacts(dto.getNominatedContacts())
            .transport(dto.getTransport())
            .goodsMovementServices(dto.getGoodsMovementServices())
            .isCuc(dto.getIsCuc())
            .billing(dto.getBilling())
            .declaration(dto.getDeclaration())
            .created(LocalDateTime.of(2026, 8, 1, 10, 0))
            .updated(LocalDateTime.of(2026, 8, 1, 11, 0))
            .expireAt(LocalDateTime.of(2026, 9, 1, 10, 0))
            .build();
    }

    private static PlantProductsOrigin origin() {
        return PlantProductsOrigin.builder()
            .countryCode("BR")
            .countryOfConsignmentCode("BR")
            .internalReference("BR-EXPORT-2026-001")
            .build();
    }

    private static PlantProductsCommodity commodity() {
        return PlantProductsCommodity.builder()
            .name("Fresh plants and plant products")
            .inputMethod(CommodityInputMethod.MANUAL)
            .commodityComplement(List.of(CommodityLine.builder()
                .uniqueComplementId("line-001")
                .commodityCode("06029050")
                .commodityDescription("Other live plants")
                .numberOfPackages(40)
                .packageType("BX")
                .quantity(new BigDecimal("125.75"))
                .quantityType("PCS")
                .netWeight(new BigDecimal("980.25"))
                .controlledAtmosphereContainer(true)
                .finishedOrPropagated(FinishedOrPropagated.FINISHED)
                .intendedForFinalUsers(true)
                .testAndTrial(false)
                .species(List.of(
                    PlantSpecies.builder()
                        .eppoCode("SOLTU")
                        .genusAndSpecies("Solanum tuberosum")
                        .speciesId("species-001")
                        .varieties(List.of(SpeciesVariety.builder()
                            .variety("Maris Piper")
                            .varietyClass(VarietyClass.CLASS_I)
                            .build()))
                        .build(),
                    PlantSpecies.builder()
                        .eppoCode("TOMLY")
                        .genusAndSpecies("Solanum lycopersicum")
                        .speciesId("species-002")
                        .varieties(List.of())
                        .build()))
                .build()))
            .build();
    }

    private static PlantProductsOperator operator(String id, String name) {
        return PlantProductsOperator.builder()
            .operatorId(id)
            .name(name)
            .telephone("+55 11 5555 0101")
            .email(id + "@example.com")
            .address(PlantProductsAddress.builder()
                .addressLine1("100 Avenida Paulista")
                .addressLine2("Building 4")
                .addressLine3("Bela Vista")
                .city("Sao Paulo")
                .postcode("01310-100")
                .country("BR")
                .build())
            .build();
    }

    private static PlantProductsContact contact(String name, boolean agent) {
        return PlantProductsContact.builder()
            .name(name)
            .email(name.toLowerCase().replace(' ', '.') + "@example.com")
            .telephone("+44 7700 900123")
            .isAgent(agent)
            .build();
    }
}
