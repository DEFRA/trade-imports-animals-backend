package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.AnimalIdentifier;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.ConsignmentParty;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Species;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.notification.Transporter;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

class GbnAgMapperTest {

    private final GbnAgEventDataMapper mapper = new GbnAgEventDataMapper();

    @Nested
    class HappyPath {

        private final GbnAgEventData result = mapper.toGbnAgEventData(fullyPopulatedNotification(), 1, List.of(
            accompanyingDocument(DocumentType.ITAHC, "ITAHC-2026-0001", "2026-05-01"),
            accompanyingDocument(DocumentType.LETTER_OF_AUTHORITY, "LOA-778", "2026-04-20")));

        @Test
        void shouldSetConstantModelAndType() {
            assertThat(result.model()).isEqualTo("defra/certificate-internal/1");
            assertThat(result.type()).isEqualTo("gbn-ag");
        }

        @Test
        void shouldMapExchangedDocumentFields() {
            ExchangedDocument doc = result.exchangedDocument();
            assertThat(doc.identifier()).isEqualTo("GBN-AG-26-7K8M2P");
            assertThat(doc.traderAssignedId()).isEqualTo("Imports456_GB");
            assertThat(doc.notificationStatusCode()).isEqualTo("SUBMITTED");
            assertThat(doc.versionId()).isEqualTo(1);
            assertThat(doc.issueDateTime()).isEqualTo("2026-05-21T10:15:00Z");
        }

        @Test
        void shouldMapNotYetCapturedExchangedDocumentFieldsToNull() {
            ExchangedDocument doc = result.exchangedDocument();
            assertThat(doc.issuer()).isNull();
        }

        @Test
        void shouldMapEachAccompanyingDocumentToAReferenceDocumentInOrder() {
            assertThat(result.exchangedDocument().referenceDocument()).containsExactly(
                new ReferencedDocument(
                    "856", "https://refdata.tbc.defra.gov.uk/gbn-ag-document-types", null, "ITAHC-2026-0001", "2026-05-01"),
                new ReferencedDocument(
                    "GBN1", "https://refdata.tbc.defra.gov.uk/gbn-ag-document-types", null, "LOA-778", "2026-04-20"));
        }

        @Test
        void shouldBuildDeclarationClausesIncludingInternalMarketPurpose() {
            List<Clause> clauses = result.exchangedDocument().firstSignatoryAuthentication().includedClause();
            assertThat(clauses).hasSize(3);
            assertThat(clauses.get(0).identifier()).isEqualTo("PURPOSE");
            assertThat(clauses.get(0).content()).isEqualTo("INTERNAL_MARKET");
            assertThat(clauses.get(1).identifier()).isEqualTo("INTERNAL_MARKET_PURPOSE");
            assertThat(clauses.get(1).content()).isNull();
            assertThat(clauses.get(2).identifier()).isEqualTo("GOODS_CERTIFIED_AS");
            assertThat(clauses.get(2).content()).isEqualTo("BREEDING_AND_PRODUCTION");
            assertThat(clauses).allSatisfy(c -> assertThat(c.urlId()).isNull());
        }

        @Test
        void shouldMapConsignorAddressToTradeAddressFields() {
            TradeParty consignor = result.specifiedConsignment().consignorParty();
            assertThat(consignor.name()).isEqualTo("Astra Rosales");
            TradeAddress address = consignor.postalAddress();
            assertThat(address.lineOne()).isEqualTo("43 East Hague Extension");
            assertThat(address.lineTwo()).isEqualTo("Delectus sit odio p");
            assertThat(address.cityName()).isEqualTo("Quas occaecat ut ear");
            assertThat(address.countryId()).isEqualTo("CH");
            assertThat(address.postcodeCode()).isEqualTo("2051");
            assertThat(address.countrySubDivisionName()).isEqualTo("Soleure");
            assertThat(address.countryName()).isNull();
            assertThat(consignor.definedContact()).isNull();
        }

        @Test
        void shouldMapDestinationToDeliveryPartyAndPlaceOfOriginToDespatchParty() {
            assertThat(result.specifiedConsignment().deliveryParty().name()).isEqualTo("Linus George Ltd");
            TradeParty despatch = result.specifiedConsignment().despatchParty();
            assertThat(despatch.name()).isEqualTo("Granja del Valle de Tenerife");
            assertThat(despatch.partyRoleCode()).isNull();
            assertThat(despatch.identifier()).isNull();
        }

        @Test
        void shouldMapConsigneeAndImporterParties() {
            TradeParty consignee = result.specifiedConsignment().consigneeParty();
            assertThat(consignee.name()).isEqualTo("Linus George Ltd");
            assertThat(consignee.postalAddress().lineOne()).isEqualTo("558 Oak Street");
            assertThat(consignee.postalAddress().countryId()).isEqualTo("CH");

            TradeParty importer = result.specifiedConsignment().importer();
            assertThat(importer.name()).isEqualTo("GB Animal Imports");
            assertThat(importer.postalAddress().lineOne()).isEqualTo("5 Port Way");
            assertThat(importer.postalAddress().countryId()).isEqualTo("GB");
        }

        @Test
        void shouldMapCarrierFromTransporter() {
            TradeParty carrier = result.specifiedConsignment().carrier();
            assertThat(carrier.name()).isEqualTo("Acme Transport Ltd");
            assertThat(carrier.partyRoleCode()).isNull();
            assertThat(carrier.identifier()).isEqualTo("UK-TR-123456");
            assertThat(carrier.urlId()).isEqualTo("https://refdata.tbc.defra.gov.uk/uk_transporter_authorisation");
            assertThat(carrier.partyTypeCode()).singleElement().satisfies(code -> {
                assertThat(code.value()).isEqualTo("COMMERCIAL");
                assertThat(code.urlId()).isEqualTo("https://traces-codelists.ec.europa.eu/operator_activity_type");
            });
        }

        @Test
        void shouldMapCountrySubdivisionToTheSubdivisionOfOriginEntry() {
            TradeCountry originCountry = result.specifiedConsignment().originCountry();
            assertThat(originCountry.code().value()).isEqualTo("ES");
            assertThat(originCountry.subordinateTradeCountrySubDivision()).singleElement().satisfies(subdivision -> {
                assertThat(subdivision.identifier()).isEqualTo("ES-CN");
                assertThat(subdivision.urlId()).isEqualTo("https://refdata.tbc.defra.gov.uk/country_subdivisions");
                assertThat(subdivision.functionTypeCode().content()).isEqualTo("106");
            });
        }

        @Test
        void shouldMapRegionOfOriginCodeToItsOwnField() {
            assertThat(result.specifiedConsignment().originCountry().regionOfOriginCode()).isEqualTo("ES-12345");
        }

        @Test
        void shouldMapCphNumberToFinalDestinationLocation() {
            LogisticsLocation destination = result.specifiedConsignment().finalDestinationLocation();
            assertThat(destination.identifier()).isEqualTo("CPH19876");
            assertThat(destination.urlId()).isEqualTo("https://refdata.tbc.defra.gov.uk/cph_number");
            assertThat(destination.name()).isNull();
            assertThat(destination.postalAddress()).isNull();
        }

        @Test
        void shouldMapTransitedCountriesToTransitTradeCountry() {
            assertThat(result.specifiedConsignment().transitTradeCountry()).satisfiesExactly(
                belgium -> assertThat(belgium.code().value()).isEqualTo("BE"),
                germany -> assertThat(germany.code().value()).isEqualTo("DE"));
        }

        @Test
        void shouldMapTransportDocumentReferenceWithTypeInferredFromMeansOfTransport() {
            LogisticsTransportMovement movement =
                result.specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst();
            assertThat(movement.transportContractRelatedReferencedDocument()).singleElement().satisfies(doc -> {
                assertThat(doc.identifier()).isEqualTo("CMR-2026-884721");
                assertThat(doc.typeCode()).isEqualTo("730");
                assertThat(doc.urlId()).isNull();
                assertThat(doc.relationshipTypeCode()).isNull();
                assertThat(doc.issueDateTime()).isNull();
            });
        }

        @Test
        void shouldMapPortOfEntryToUnloadingBaseport() {
            LogisticsLocation port = result.specifiedConsignment().unloadingBaseportLocation();
            assertThat(port.identifier()).isEqualTo("GBDVR");
            assertThat(port.urlId()).isNull();
        }

        @Test
        void shouldMapTransportMovementWithIntegerModeCodeAndArrivalEvent() {
            LogisticsTransportMovement movement =
                result.specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst();
            assertThat(movement.modeCode()).isEqualTo(3);
            assertThat(movement.usedLogisticsTransportMeans().name()).isEqualTo("AB-1234");
            TransportEvent arrival = movement.arrivalEvent().getFirst();
            assertThat(arrival.scheduledOccurrenceDateTime()).isEqualTo("2026-05-06T00:00:00Z");
            assertThat(arrival.occurrenceLogisticsLocation()).isNull();
        }

        @Test
        void shouldConvertUnweanedAnimalsStringToBoolean() {
            assertThat(result.specifiedConsignment().isOrHasUnweanedAnimals()).isTrue();
        }

        @Test
        void shouldMapCommodityToSingleConsignmentItemWithTradeLine() {
            List<ConsignmentItem> items = result.specifiedConsignment().includedConsignmentItem();
            assertThat(items).hasSize(1);
            TradeLineItem line = items.getFirst().includedTradeLineItem().getFirst();

            assertThat(line.description()).containsExactly("Live bovine animals");
            assertThat(line.commonName()).isEqualTo("Domestic cattle");
            assertThat(line.scientificName()).isEqualTo("Bos taurus");
            assertThat(line.typeCode()).isNull();
            assertThat(line.urlId()).isNull();
            assertThat(line.applicableClassification()).singleElement().satisfies(c -> {
                assertThat(c.systemId()).isEqualTo("CN");
                assertThat(c.classCode().value()).isEqualTo("01020000");
                assertThat(c.classCode().urlId()).isNull();
            });
            assertThat(line.specifiedLineTradeDelivery().getFirst().productUnitQuantity())
                .satisfies(q -> {
                    assertThat(q.content()).isEqualTo(20);
                    assertThat(q.unitCode()).isNull();
                });
            assertThat(line.physicalReferencedLogisticsPackage().getFirst().itemQuantity()).isEqualTo(1);
        }

        @Test
        void shouldReshapeEachAnimalsEarTagAndPassportIntoItsOwnProductInstance() {
            TradeLineItem line = result.specifiedConsignment()
                .includedConsignmentItem().getFirst().includedTradeLineItem().getFirst();
            List<TradeProductInstance> instances = line.individualTradeProductInstance();
            assertThat(instances).hasSize(2);
            assertThat(instances.getFirst().identifier()).satisfiesExactly(
                earTag -> {
                    assertThat(earTag.typeCode()).isEqualTo("EAR_TAG");
                    assertThat(earTag.content()).isEqualTo("UK01234567890");
                },
                passport -> {
                    assertThat(passport.typeCode()).isEqualTo("PASSPORT");
                    assertThat(passport.content()).isEqualTo("UK0123456700999");
                });
            assertThat(instances.getLast().identifier()).satisfiesExactly(
                earTag -> {
                    assertThat(earTag.typeCode()).isEqualTo("EAR_TAG");
                    assertThat(earTag.content()).isEqualTo("UK01234567891");
                },
                passport -> {
                    assertThat(passport.typeCode()).isEqualTo("PASSPORT");
                    assertThat(passport.content()).isEqualTo("UK0123456700998");
                });
            assertThat(instances.getFirst().name()).isNull();
            assertThat(instances.getFirst().permanentLocation()).isNull();
        }
    }

    @Nested
    class MinimalAndDefaults {

        private final GbnAgEventData result = mapper.toGbnAgEventData(
            NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-MIN001")
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .build(), null, List.of());

        @Test
        void shouldStillSetConstantsAndIdentifier() {
            assertThat(result.model()).isEqualTo("defra/certificate-internal/1");
            assertThat(result.type()).isEqualTo("gbn-ag");
            assertThat(result.exchangedDocument().identifier()).isEqualTo("GBN-AG-26-MIN001");
            assertThat(result.exchangedDocument().notificationStatusCode()).isEqualTo("DRAFT");
            assertThat(result.exchangedDocument().versionId()).isNull();
        }

        @Test
        void shouldMapAbsentDomainFieldsToNullWithoutError() {
            ExchangedDocument doc = result.exchangedDocument();
            assertThat(doc.traderAssignedId()).isNull();
            assertThat(doc.issueDateTime()).isNull();
            assertThat(doc.issuer()).isNull();
            assertThat(doc.referenceDocument()).isNull();

            SpecifiedConsignment consignment = result.specifiedConsignment();
            assertThat(consignment.consignorParty()).isNull();
            assertThat(consignment.consigneeParty()).isNull();
            assertThat(consignment.deliveryParty()).isNull();
            assertThat(consignment.despatchParty()).isNull();
            assertThat(consignment.importer()).isNull();
            assertThat(consignment.carrier()).isNull();
            assertThat(consignment.originCountry()).isNull();
            assertThat(consignment.unloadingBaseportLocation()).isNull();
            assertThat(consignment.finalDestinationLocation()).isNull();
            assertThat(consignment.mainCarriageLogisticsTransportMovement()).isNull();
            assertThat(consignment.transitTradeCountry()).isNull();
            assertThat(consignment.isOrHasUnweanedAnimals()).isNull();
            assertThat(consignment.includedConsignmentItem()).isNull();
        }

        @Test
        void shouldEmitTwoRequiredClausesWithNullContent_whenReasonNotInternalMarket() {
            List<Clause> clauses = result.exchangedDocument().firstSignatoryAuthentication().includedClause();
            assertThat(clauses).extracting(Clause::identifier)
                .containsExactly("PURPOSE", "GOODS_CERTIFIED_AS");
            assertThat(clauses).allSatisfy(c -> assertThat(c.content()).isNull());
        }
    }

    @Test
    void toGbnAgEventData_shouldReturnNull_whenNotificationNull() {
        assertThat(mapper.toGbnAgEventData(null, null, List.of())).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "VETERINARY_HEALTH_CERTIFICATE, 853",
        "HEALTH_CERTIFICATE, 636",
        "AIR_WAYBILL, 740",
        "SEA_WAYBILL, 710",
        "RAIL_WAYBILL, 720",
        "BILL_OF_LADING, 705",
        "COMMERCIAL_INVOICE, 380",
        "ITAHC, 856",
        "IMPORT_PERMIT, 911",
        "LETTER_OF_AUTHORITY, GBN1",
        "CATCH_CERTIFICATE, GBN2",
        "LABORATORY_SAMPLING_RESULTS_FOR_AFLATOXIN, 4",
        "JOURNEY_LOG, GBN3",
        "OTHER, 916"
    })
    void shouldCodeEachDocumentTypeFromTheDocumentTypeCodelist(DocumentType type, String typeCode) {
        ReferencedDocument document = mapper.toGbnAgEventData(
                fullyPopulatedNotification(), 1, List.of(accompanyingDocument(type, "REF-1", "2026-05-01")))
            .exchangedDocument().referenceDocument().getFirst();

        assertThat(document.typeCode()).isEqualTo(typeCode);
        assertThat(document.urlId()).isEqualTo("https://refdata.tbc.defra.gov.uk/gbn-ag-document-types");
    }

    @Test
    void shouldHaveACodelistRowForEveryDocumentType() {
        assertThat(DocumentType.values()).hasSize(14);
    }

    @Test
    void shouldLeaveIssueDateOut_whenDocumentHasNoDateOfIssue() {
        AccompanyingDocument undated = AccompanyingDocument.builder()
            .documentType(DocumentType.OTHER).documentReference("REF-2").build();

        ReferencedDocument document = mapper.toGbnAgEventData(fullyPopulatedNotification(), 1, List.of(undated))
            .exchangedDocument().referenceDocument().getFirst();

        assertThat(document.issueDateTime()).isNull();
    }

    @Test
    void shouldEmitIssueDateTimeWithItsSubMillisecondDigitsIntact() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-NANO01")
            .status(NotificationStatus.SUBMITTED)
            .updated(Instant.parse("2026-05-21T10:15:00.123456789Z"))
            .notification(Notification.builder().build())
            .build();

        assertThat(mapper.toGbnAgEventData(notificationAggregate, 1, List.of()).exchangedDocument().issueDateTime())
            .isEqualTo("2026-05-21T10:15:00.123456789Z");
    }

    @Test
    void shouldEmitArrivalDateAsUtcMidnight_whateverTheJvmDefaultZone() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-ARV001")
            .notification(Notification.builder()
                .transport(Transport.builder()
                    .arrivalDate(LocalDate.parse("2026-07-21"))
                    .build())
                .build())
            .build();

        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        try {
            TransportEvent arrival = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst()
            .arrivalEvent().getFirst();

            assertThat(arrival.scheduledOccurrenceDateTime()).isEqualTo("2026-07-21T00:00:00Z");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @ParameterizedTest
    @CsvSource({"VESSEL,1", "RAILWAY,2", "ROAD_VEHICLE,3", "AIRPLANE,4"})
    void shouldMapMeansOfTransportToUnRec19ModeCode(MeansOfTransport means, int expectedCode) {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-MODE01")
            .notification(Notification.builder()
                .transport(Transport.builder().meansOfTransport(means).build())
                .build())
            .build();

        Integer modeCode = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst().modeCode();

        assertThat(modeCode).isEqualTo(expectedCode);
    }

    @Test
    void shouldMapLogisticsPackageToNull_whenTotalNoOfPackagesNull() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-PKG001")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .totalNoOfPackages(null)
                        .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.physicalReferencedLogisticsPackage()).isNull();
    }

    @Test
    void shouldMapIndividualTradeProductInstanceToNull_whenSpeciesAbsent() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SPC001")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .species(null)
                        .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.individualTradeProductInstance()).isNull();
        assertThat(line.scientificName()).isNull();
    }

    @Test
    void shouldMapApplicableClassificationToNull_whenCommodityCodeAbsent() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-CN001")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity(null)
                        .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.applicableClassification()).isNull();
    }

    @Test
    void shouldMapScientificNameToNull_whenSpeciesEmpty() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SPC004")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .species(List.of())
                        .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.scientificName()).isNull();
    }

    @Test
    void shouldMapDescriptionOntoTheComplementTotalsLine_whenSpeciesEmpty() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SPC005")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .commodityCode("01020000")
                        .description("Live bovine animals")
                        .species(List.of())
                        .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.description()).containsExactly("Live bovine animals");
        assertThat(line.commonName()).isNull();
        assertThat(line.scientificName()).isNull();
    }

    @Test
    void shouldMapIncludedConsignmentItemToNull_whenCommoditiesEmpty() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-EMPTY")
            .notification(Notification.builder()
                .commodities(List.of())
                .build())
            .build();

        assertThat(mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem()).isNull();
    }

    @Test
    void shouldPreferSpeciesTypeOfCommodityAsTradeLineTypeCode() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-TYPE")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("COMPLEMENT-TYPE")
                        .species(List.of(Species.builder()
                            .value("1148346")
                            .text("Bos taurus")
                            .typeOfCommodity("SPECIES-TYPE")
                            .build()))
                        .build()))
                .build())
            .build();

        TradeLineItem line = firstLineOf(notificationAggregate);

        assertThat(line.typeCode()).isEqualTo("SPECIES-TYPE");
    }

    @Test
    void shouldFallBackToComplementTypeOfCommodity_whenSpeciesTypeBlank() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-TYPE2")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("COMPLEMENT-TYPE")
                        .species(List.of(Species.builder()
                            .value("1148346")
                            .text("Bos taurus")
                            .typeOfCommodity("  ")
                            .build()))
                        .build()))
                .build())
            .build();

        assertThat(firstLineOf(notificationAggregate).typeCode()).isEqualTo("COMPLEMENT-TYPE");
    }

    @Test
    void shouldOmitBlankCommonNameDescriptionAndClassification() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-BLANK")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .commodityCode("   ")
                        .description("   ")
                        .species(List.of(Species.builder()
                            .value("1148346")
                            .text("Bos taurus")
                            .commonName("   ")
                            .build()))
                        .build()))
                .build())
            .build();

        TradeLineItem line = firstLineOf(notificationAggregate);

        assertThat(line.applicableClassification()).isNull();
        assertThat(line.description()).isNull();
        assertThat(line.commonName()).isNull();
    }

    @Test
    void shouldMapCommonNameToNull_whenCommodityNameAbsent() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-CMD001")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.commonName()).isNull();
        assertThat(line.description()).isNull();
    }

    @Test
    void shouldMapEachSpeciesToItsOwnTradeLineWithItsOwnCounts() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SCI001")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .commodityCode("0102")
                        .totalNoOfAnimals(15)
                        .totalNoOfPackages(3)
                        .species(List.of(
                            Species.builder().value("1148346").text("Bos taurus")
                                .noOfAnimals(12).noOfPackages(1).build(),
                            Species.builder().value("749313").text("Bubalus bubalis")
                                .noOfAnimals(3).noOfPackages(2).build()))
                        .build()))
                .build())
            .build();

        List<TradeLineItem> lines = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst().includedTradeLineItem();

        assertThat(lines).satisfiesExactly(
            cattle -> {
                assertThat(cattle.scientificName()).isEqualTo("Bos taurus");
                assertThat(cattle.specifiedLineTradeDelivery().getFirst().productUnitQuantity().content()).isEqualTo(12);
                assertThat(cattle.physicalReferencedLogisticsPackage().getFirst().itemQuantity()).isEqualTo(1);
            },
            buffalo -> {
                assertThat(buffalo.scientificName()).isEqualTo("Bubalus bubalis");
                assertThat(buffalo.specifiedLineTradeDelivery().getFirst().productUnitQuantity().content()).isEqualTo(3);
                assertThat(buffalo.physicalReferencedLogisticsPackage().getFirst().itemQuantity()).isEqualTo(2);
            });
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.applicableClassification()).singleElement()
                .satisfies(c -> assertThat(c.classCode().value()).isEqualTo("0102"));
            assertThat(line.commonName()).isNull();
            assertThat(line.description()).isNull();
        });
    }

    @Test
    void shouldMapHorseNameAndIdentifiersForEachHorse() {
        NotificationAggregate notificationAggregate = speciesNotification("GBN-AG-26-HRS001",
            Species.builder().value("822332").text("Equus caballus")
                .animalIdentifiers(List.of(
                    AnimalIdentifier.builder()
                        .microchip("826003200123456").passport("GBR-826-0012345").horseName("Starlight").build(),
                    AnimalIdentifier.builder()
                        .microchip("826003200654321").passport("GBR-826-0054321").horseName("Moonbeam").build()))
                .build());

        TradeLineItem line = firstLineOf(notificationAggregate);

        assertThat(line.scientificName()).isEqualTo("Equus caballus");
        assertThat(line.individualTradeProductInstance()).satisfiesExactly(
            starlight -> {
                assertThat(starlight.name()).isEqualTo("Starlight");
                assertThat(starlight.identifier()).satisfiesExactly(
                    passport -> {
                        assertThat(passport.typeCode()).isEqualTo("PASSPORT");
                        assertThat(passport.content()).isEqualTo("GBR-826-0012345");
                    },
                    microchip -> {
                        assertThat(microchip.typeCode()).isEqualTo("MICROCHIP");
                        assertThat(microchip.content()).isEqualTo("826003200123456");
                    });
            },
            moonbeam -> {
                assertThat(moonbeam.name()).isEqualTo("Moonbeam");
                assertThat(moonbeam.identifier()).satisfiesExactly(
                    passport -> {
                        assertThat(passport.typeCode()).isEqualTo("PASSPORT");
                        assertThat(passport.content()).isEqualTo("GBR-826-0054321");
                    },
                    microchip -> {
                        assertThat(microchip.typeCode()).isEqualTo("MICROCHIP");
                        assertThat(microchip.content()).isEqualTo("826003200654321");
                    });
            });
    }

    @Test
    void shouldMapTattooAndPermanentAddressForEachDog() {
        NotificationAggregate notificationAggregate = speciesNotification("GBN-AG-26-DOG001",
            Species.builder().value("923502").text("Canis lupus familiaris")
                .animalIdentifiers(List.of(AnimalIdentifier.builder()
                    .microchip("900123456789012")
                    .tattoo("TT-4471")
                    .permanentAddress(ConsignmentParty.builder()
                        .name("Brighton home")
                        .phone("01273 555017")
                        .email("owner@example.co.uk")
                        .address(Address.builder()
                            .addressLine1("182 Ditchling Road")
                            .townOrCity("Brighton")
                            .postcode("BN1 7JE")
                            .countryCode("GB")
                            .build())
                        .build())
                    .build()))
                .build());

        TradeLineItem line = firstLineOf(notificationAggregate);
        TradeProductInstance dog = line.individualTradeProductInstance().getFirst();

        assertThat(line.scientificName()).isEqualTo("Canis lupus familiaris");
        assertThat(dog.name()).isNull();
        assertThat(dog.identifier()).satisfiesExactly(
            microchip -> {
                assertThat(microchip.typeCode()).isEqualTo("MICROCHIP");
                assertThat(microchip.content()).isEqualTo("900123456789012");
            },
            tattoo -> {
                assertThat(tattoo.typeCode()).isEqualTo("TATTOO");
                assertThat(tattoo.content()).isEqualTo("TT-4471");
            });
        assertThat(dog.permanentLocation()).satisfies(home -> {
            assertThat(home.name()).isEqualTo("Brighton home");
            assertThat(home.postalAddress().lineOne()).isEqualTo("182 Ditchling Road");
            assertThat(home.postalAddress().cityName()).isEqualTo("Brighton");
            assertThat(home.postalAddress().postcodeCode()).isEqualTo("BN1 7JE");
            assertThat(home.definedContact()).singleElement().satisfies(contact -> {
                assertThat(contact.telephoneUniversalCommunication()).isEqualTo("01273 555017");
                assertThat(contact.emailURIUniversalCommunication()).isEqualTo("owner@example.co.uk");
            });
        });
    }

    @Test
    void shouldSkipIdentifierLeftUnanswered_asTheJourneySavesItAsAnEmptyString() {
        NotificationAggregate notificationAggregate = speciesNotification("GBN-AG-26-BLK002",
            Species.builder().value("1148346").text("Bos taurus")
                .earTag("UK123456789012").passport("")
                .animalIdentifiers(List.of(AnimalIdentifier.builder()
                    .earTag("UK123456789012").passport("").build()))
                .build());

        List<TradeProductInstance> instances = firstLineOf(notificationAggregate).individualTradeProductInstance();

        assertThat(instances).singleElement().satisfies(cow ->
            assertThat(cow.identifier()).singleElement().satisfies(earTag -> {
                assertThat(earTag.typeCode()).isEqualTo("EAR_TAG");
                assertThat(earTag.content()).isEqualTo("UK123456789012");
            }));
    }

    @ParameterizedTest
    @CsvSource({"VESSEL,705", "RAILWAY,720", "ROAD_VEHICLE,730", "AIRPLANE,740"})
    void shouldInferTransportDocumentTypeFromMeansOfTransport(MeansOfTransport means, String expectedTypeCode) {
        List<ReferencedDocument> documents = transportDocumentsFor(
            Transport.builder().meansOfTransport(means).transportDocumentReference("DOC-1").build());

        assertThat(documents).singleElement().satisfies(doc -> {
            assertThat(doc.typeCode()).isEqualTo(expectedTypeCode);
            assertThat(doc.identifier()).isEqualTo("DOC-1");
        });
    }

    @Test
    void shouldLeaveTransportDocumentTypeUnset_whenMeansOfTransportUnknown() {
        List<ReferencedDocument> documents = transportDocumentsFor(
            Transport.builder().transportDocumentReference("DOC-1").build());

        assertThat(documents).singleElement().satisfies(doc -> {
            assertThat(doc.typeCode()).isNull();
            assertThat(doc.identifier()).isEqualTo("DOC-1");
        });
    }

    @Test
    void shouldOmitTransportDocument_whenReferenceBlank() {
        assertThat(transportDocumentsFor(Transport.builder()
            .meansOfTransport(MeansOfTransport.VESSEL).transportDocumentReference(" ").build())).isNull();
    }

    @Test
    void shouldOmitTransitTradeCountry_whenNoCountriesTransited() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-TRN001")
            .notification(Notification.builder()
                .transport(Transport.builder().transitedCountries(List.of()).build())
                .build())
            .build();

        assertThat(mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().transitTradeCountry()).isNull();
    }

    @Test
    void shouldOmitFinalDestinationLocationSubdivisionAndRegionCode_whenBlank() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-BLK001")
            .notification(Notification.builder()
                .origin(Origin.builder().countryCode("FR").countrySubdivisionCode(" ").requiresRegionCode("false").internalReference("Imports456_GB").regionOfOriginCode(" ").build())
                .cphNumber(" ")
                .build())
            .build();

        SpecifiedConsignment consignment = mapper.toGbnAgEventData(notificationAggregate, 1, List.of()).specifiedConsignment();

        assertThat(consignment.finalDestinationLocation()).isNull();
        assertThat(consignment.originCountry().subordinateTradeCountrySubDivision()).isNull();
        assertThat(consignment.originCountry().regionOfOriginCode()).isNull();
    }

    @Test
    void shouldCarryRegionOfOriginCodeWithoutSubdivision_whenWholeCountryOrigin() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-WHL001")
            .notification(Notification.builder()
                .origin(Origin.builder().countryCode("FR").requiresRegionCode("yes").regionOfOriginCode("FR-12345").build())
                .build())
            .build();

        TradeCountry originCountry = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().originCountry();

        assertThat(originCountry.code().value()).isEqualTo("FR");
        assertThat(originCountry.subordinateTradeCountrySubDivision()).isNull();
        assertThat(originCountry.regionOfOriginCode()).isEqualTo("FR-12345");
    }

    @Test
    void shouldCarrySubdivisionWithoutRegionOfOriginCode_whenNoCodeOnCertificate() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SUB001")
            .notification(Notification.builder()
                .origin(Origin.builder().countryCode("ES").countrySubdivisionCode("ES-CN").requiresRegionCode("false").build())
                .build())
            .build();

        TradeCountry originCountry = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().originCountry();

        assertThat(originCountry.subordinateTradeCountrySubDivision()).singleElement()
            .satisfies(region -> assertThat(region.identifier()).isEqualTo("ES-CN"));
        assertThat(originCountry.regionOfOriginCode()).isNull();
    }

    @Test
    void shouldCarryNeitherSubdivisionNorRegionOfOriginCode_onTransitCountries() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-TRN002")
            .notification(Notification.builder()
                .transport(Transport.builder().transitedCountries(List.of("FR", "BE")).build())
                .build())
            .build();

        assertThat(mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().transitTradeCountry())
            .extracting(TradeCountry::subordinateTradeCountrySubDivision, TradeCountry::regionOfOriginCode)
            .containsOnly(tuple(null, null));
    }

    // The frontend keeps a code entered before the trader answered "no", so a stored code alone
    // must not reach the event.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"no", "No", "false"})
    void shouldNotCarryStoredRegionOfOriginCode_whenRequirementIsNotYes(String requiresRegionCode) {
        assertThat(originCountryFor(Origin.builder().countryCode("FR")
            .requiresRegionCode(requiresRegionCode).regionOfOriginCode("FR-12345").build())
            .regionOfOriginCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "YES"})
    void shouldCarryRegionOfOriginCode_whenRequirementIsYesInAnyCase(String requiresRegionCode) {
        assertThat(originCountryFor(Origin.builder().countryCode("FR")
            .requiresRegionCode(requiresRegionCode).regionOfOriginCode("FR-12345").build())
            .regionOfOriginCode()).isEqualTo("FR-12345");
    }

    @Test
    void shouldOmitAbsentSubdivisionAndRegionOfOriginCode_whenSerialised() {
        TradeCountry wholeCountry = originCountryFor(Origin.builder().countryCode("FR").requiresRegionCode("no").build());
        TradeCountry subdivision = originCountryFor(Origin.builder().countryCode("ES").countrySubdivisionCode("ES-CN").build());

        JsonNode wholeCountryJson = new ObjectMapper().valueToTree(wholeCountry);
        JsonNode subdivisionJson = new ObjectMapper().valueToTree(subdivision);

        assertThat(wholeCountryJson.has("subordinateTradeCountrySubDivision")).isFalse();
        assertThat(wholeCountryJson.has("regionOfOriginCode")).isFalse();
        assertThat(subdivisionJson.has("regionOfOriginCode")).isFalse();
        assertThat(subdivisionJson.path("subordinateTradeCountrySubDivision").path(0).fieldNames())
            .toIterable().containsExactlyInAnyOrder("identifier", "urlId", "functionTypeCode");
    }

    private TradeCountry originCountryFor(Origin origin) {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-ORG001")
            .notification(Notification.builder().origin(origin).build())
            .build();
        return mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().originCountry();
    }

    private List<ReferencedDocument> transportDocumentsFor(Transport transport) {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-DOC001")
            .notification(Notification.builder().transport(transport).build())
            .build();
        return mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst()
            .transportContractRelatedReferencedDocument();
    }

    @Test
    void shouldMapIdentifiersToNull_whenSpeciesCarriesNoEarTagPassportOrMicrochip() {
        NotificationAggregate notificationAggregate = speciesNotification("GBN-AG-26-SPC002",
            Species.builder().value("BOV").text("Cattle").build());

        List<TradeProductInstance> instances = firstLineOf(notificationAggregate)
            .individualTradeProductInstance();

        assertThat(instances).singleElement().satisfies(instance -> {
            assertThat(instance.identifier()).isNull();
            assertThat(instance.name()).isNull();
            assertThat(instance.permanentLocation()).isNull();
        });
    }

    @Test
    void shouldMapMicrochipOnly_whenSpeciesCarriesNoEarTagOrPassport() {
        NotificationAggregate notificationAggregate = speciesNotification("GBN-AG-26-SPC003",
            Species.builder().value("CAN").text("Dogs").microchip("900123456789012").build());

        List<TradeProductInstance> instances = firstLineOf(notificationAggregate)
            .individualTradeProductInstance();

        assertThat(instances).singleElement().satisfies(instance ->
            assertThat(instance.identifier()).singleElement().satisfies(microchip -> {
                assertThat(microchip.typeCode()).isEqualTo("MICROCHIP");
                assertThat(microchip.content()).isEqualTo("900123456789012");
                assertThat(microchip.urlId()).isNull();
            }));
    }

    private static NotificationAggregate speciesNotification(String reference, Species species) {
        return NotificationAggregate.builder()
            .referenceNumber(reference)
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .species(List.of(species))
                        .build()))
                .build())
            .build();
    }

    private TradeLineItem firstLineOf(NotificationAggregate notificationAggregate) {
        return mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();
    }

    @Test
    void shouldMapEachCommodityEntryToTradeLinesWithMatchingNameAndCnCode() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-MULTI")
            .notification(Notification.builder()
                .commodities(List.of(
                    CommodityComplement.builder()
                        .name("Cow")
                        .commodityCode("0102")
                        .description("Live bovine animals")
                        .species(List.of(Species.builder()
                            .value("1148346")
                            .text("Bos taurus")
                            .commonName("Domestic cattle")
                            .noOfAnimals(1)
                            .noOfPackages(1)
                            .build()))
                        .build(),
                    CommodityComplement.builder()
                        .name("Cat")
                        .commodityCode("01061900")
                        .description("Other live animals")
                        .species(List.of(Species.builder()
                            .value("923501")
                            .text("Felis catus")
                            .commonName("Cat")
                            .noOfAnimals(1)
                            .noOfPackages(1)
                            .build()))
                        .build()))
                .build())
            .build();

        List<TradeLineItem> lines = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().includedConsignmentItem().getFirst().includedTradeLineItem();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).commonName()).isEqualTo("Domestic cattle");
        assertThat(lines.get(0).description()).containsExactly("Live bovine animals");
        assertThat(lines.get(0).applicableClassification().getFirst().classCode().value()).isEqualTo("0102");
        assertThat(lines.get(1).commonName()).isEqualTo("Cat");
        assertThat(lines.get(1).description()).containsExactly("Other live animals");
        assertThat(lines.get(1).applicableClassification().getFirst().classCode().value()).isEqualTo("01061900");
    }

    @Test
    void shouldOmitClassification_whenOnlyTypeOfCommodityIsSet() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-LEGACY")
            .notification(Notification.builder()
                .commodities(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .totalNoOfAnimals(1)
                        .totalNoOfPackages(1)
                        .species(List.of(Species.builder()
                            .value("1148346")
                            .text("Bos taurus")
                            .noOfAnimals(1)
                            .noOfPackages(1)
                            .build()))
                        .build()))
                .build())
            .build();

        TradeLineItem line = firstLineOf(notificationAggregate);
        assertThat(line.applicableClassification()).isNull();
        assertThat(line.typeCode()).isEqualTo("01020000");
    }

    private static NotificationAggregate fullyPopulatedNotification() {
        return NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-7K8M2P")
            .status(NotificationStatus.SUBMITTED)
            .updated(Instant.parse("2026-05-21T10:15:00Z"))
            .notification(Notification.builder()
                .origin(Origin.builder().countryCode("ES").countrySubdivisionCode("ES-CN").requiresRegionCode("yes").internalReference("Imports456_GB").regionOfOriginCode("ES-12345").build())
                .reasonForImport("INTERNAL_MARKET")
                .additionalDetails(new AdditionalDetails("BREEDING_AND_PRODUCTION", "true"))
                .consignor(party("Astra Rosales",
                    Address.builder()
                        .addressLine1("43 East Hague Extension")
                        .addressLine2("Delectus sit odio p")
                        .townOrCity("Quas occaecat ut ear")
                        .county("Soleure")
                        .postcode("2051")
                        .countryCode("CH")
                        .build()))
                .consignee(party("Linus George Ltd", simpleAddress("558 Oak Street", "CH")))
                .destination(party("Linus George Ltd", simpleAddress("558 Oak Street", "CH")))
                .importer(party("GB Animal Imports", simpleAddress("5 Port Way", "GB")))
                .placeOfOrigin(party("Granja del Valle de Tenerife", simpleAddress("Camino La Hornera 12", "ES")))
                .consignment(party("Animal and Plant Health Agency", simpleAddress("Woodham Lane", "GB")))
                .cphNumber("CPH19876")
                .commodities(List.of(CommodityComplement.builder()
                    .name("Cow")
                    .commodityCode("01020000")
                    .description("Live bovine animals")
                    .totalNoOfAnimals(20)
                    .totalNoOfPackages(1)
                    .species(List.of(Species.builder()
                        .value("1148346")
                        .text("Bos taurus")
                        .commonName("Domestic cattle")
                        .noOfAnimals(20)
                        .noOfPackages(1)
                        .earTag("UK01234567890")
                        .passport("UK0123456700999")
                        .animalIdentifiers(List.of(
                            AnimalIdentifier.builder()
                                .earTag("UK01234567890").passport("UK0123456700999").build(),
                            AnimalIdentifier.builder()
                                .earTag("UK01234567891").passport("UK0123456700998").build()))
                        .build()))
                    .build()))
                .transport(Transport.builder()
                    .portOfEntry("GBDVR")
                    .arrivalDate(LocalDate.parse("2026-05-06"))
                    .meansOfTransport(MeansOfTransport.ROAD_VEHICLE)
                    .transportIdentification("AB-1234")
                    .transportDocumentReference("CMR-2026-884721")
                    .transitedCountries(List.of("BE", "DE"))
                    .transporter(Transporter.builder()
                        .name("Acme Transport Ltd")
                        .address(simpleAddress("1 Haulage Way", "GB"))
                        .approvalNumber("UK-TR-123456")
                        .type("COMMERCIAL")
                        .build())
                    .build())
                .build())
            .build();
    }

    @Test
    void shouldMapInlineParty_toConsignorNameAndPostalAddress() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-REFRES")
            .notification(Notification.builder()
                .consignor(ConsignmentParty.builder()
                    .name("Astra Rosales")
                    .address(Address.builder()
                        .addressLine1("43 East Hague Extension")
                        .townOrCity("Vernier")
                        .postcode("30055")
                        .countryCode("CH")
                        .build())
                    .build())
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().consignorParty();

        assertThat(consignor.name()).isEqualTo("Astra Rosales");
        assertThat(consignor.postalAddress().lineOne()).isEqualTo("43 East Hague Extension");
        assertThat(consignor.postalAddress().cityName()).isEqualTo("Vernier");
        assertThat(consignor.postalAddress().countryId()).isEqualTo("CH");
    }

    @Test
    void shouldMapPartyEmailAndPhoneToDefinedContact() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-CONTACT")
            .notification(Notification.builder()
                .consignor(ConsignmentParty.builder()
                    .name("Astra Rosales")
                    .email("astra@example.com")
                    .phone("01632 960111")
                    .address(simpleAddress("43 East Hague Extension", "CH"))
                    .build())
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().consignorParty();

        assertThat(consignor.definedContact()).singleElement().satisfies(contact -> {
            assertThat(contact.emailURIUniversalCommunication()).isEqualTo("astra@example.com");
            assertThat(contact.telephoneUniversalCommunication()).isEqualTo("01632 960111");
            assertThat(contact.personName()).isNull();
        });
    }

    @Test
    void shouldMapDefinedContact_whenOnlyOneOfEmailOrPhoneIsHeld() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-PHONLY")
            .notification(Notification.builder()
                .consignor(ConsignmentParty.builder()
                    .name("Astra Rosales")
                    .phone("01632 960111")
                    .address(simpleAddress("43 East Hague Extension", "CH"))
                    .build())
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1, List.of())
            .specifiedConsignment().consignorParty();

        assertThat(consignor.definedContact()).singleElement().satisfies(contact -> {
            assertThat(contact.telephoneUniversalCommunication()).isEqualTo("01632 960111");
            assertThat(contact.emailURIUniversalCommunication()).isNull();
        });
    }

    private static ConsignmentParty party(String name, Address address) {
        return ConsignmentParty.builder().name(name).address(address).build();
    }

    private static Address simpleAddress(String line1, String countryCode) {
        return Address.builder().addressLine1(line1).countryCode(countryCode).build();
    }

    private static AccompanyingDocument accompanyingDocument(DocumentType type, String reference, String dateOfIssue) {
        return AccompanyingDocument.builder()
            .documentType(type)
            .documentReference(reference)
            .dateOfIssue(LocalDate.parse(dateOfIssue))
            .scanStatus(ScanStatus.COMPLETE)
            .build();
    }
}
