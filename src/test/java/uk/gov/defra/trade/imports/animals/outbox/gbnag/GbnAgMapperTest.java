package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.AnimalIdentifier;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
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
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

class GbnAgMapperTest {

    private final GbnAgEventDataMapper mapper = new GbnAgEventDataMapper();

    @Nested
    class HappyPath {

        private final GbnAgEventData result = mapper.toGbnAgEventData(fullyPopulatedNotification(), 1);

        @Test
        void shouldSetConstantModelAndType() {
            assertThat(result.model()).isEqualTo("defra/certificate-internal/1");
            assertThat(result.type()).isEqualTo("gbn-ag");
        }

        @Test
        void shouldMapExchangedDocumentFields() {
            ExchangedDocument doc = result.exchangedDocument();
            assertThat(doc.identifier()).isEqualTo("GBN-AG-26-7K8M2P");
            assertThat(doc.traderAssignedId()).isEqualTo("Imports456_GB");   // <- origin.internalReference
            assertThat(doc.notificationStatusCode()).isEqualTo("SUBMITTED");
            assertThat(doc.versionId()).isEqualTo(1);
            assertThat(doc.issueDateTime()).isEqualTo("2026-05-21T10:15:00Z"); // updated, stamped UTC
        }

        @Test
        void shouldMapNotYetCapturedExchangedDocumentFieldsToNull() {
            ExchangedDocument doc = result.exchangedDocument();
            // The fixture sets a consignment contact, but where it belongs on the event is still
            // an open question, so it is deliberately not mapped to issuer yet.
            assertThat(doc.issuer()).isNull();            // gap G1
            assertThat(doc.referenceDocument()).isNull(); // gap G3
        }

        @Test
        void shouldBuildDeclarationClausesIncludingInternalMarketPurpose() {
            List<Clause> clauses = result.exchangedDocument().firstSignatoryAuthentication().includedClause();
            assertThat(clauses).hasSize(3);
            assertThat(clauses.get(0).identifier()).isEqualTo("PURPOSE");
            assertThat(clauses.get(0).content()).isEqualTo("INTERNAL_MARKET");
            // Required when PURPOSE=INTERNAL_MARKET; sub-purpose not in domain (gap G2)
            assertThat(clauses.get(1).identifier()).isEqualTo("INTERNAL_MARKET_PURPOSE");
            assertThat(clauses.get(1).content()).isNull();
            assertThat(clauses.get(2).identifier()).isEqualTo("GOODS_CERTIFIED_AS");
            assertThat(clauses.get(2).content()).isEqualTo("BREEDING_AND_PRODUCTION");
            assertThat(clauses).allSatisfy(c -> assertThat(c.urlId()).isNull());
        }

        @Test
        void shouldMapPartiesWithRenamedAddressFields() {
            TradeParty consignor = result.specifiedConsignment().consignorParty();
            assertThat(consignor.name()).isEqualTo("Astra Rosales");
            TradeAddress address = consignor.postalAddress();
            assertThat(address.lineOne()).isEqualTo("43 East Hague Extension");
            assertThat(address.lineTwo()).isEqualTo("Delectus sit odio p");
            assertThat(address.cityName()).isEqualTo("Quas occaecat ut ear");
            assertThat(address.countryId()).isEqualTo("CH");
            // EUDPA-294 added postcode and county to the domain model, so these two are no
            // longer the hardcoded nulls they were when the fields did not exist.
            assertThat(address.postcodeCode()).isEqualTo("2051");
            assertThat(address.countrySubDivisionName()).isEqualTo("Soleure");
            assertThat(address.countryName()).isNull(); // still a gap - the domain holds the code only
            // This party carries no email or phone, so there is no contact to emit.
            assertThat(consignor.definedContact()).isNull();
        }

        @Test
        void shouldMapDestinationToDeliveryPartyAndPlaceOfOriginToDespatchParty() {
            assertThat(result.specifiedConsignment().deliveryParty().name()).isEqualTo("Linus George Ltd");
            TradeParty despatch = result.specifiedConsignment().despatchParty();
            assertThat(despatch.name()).isEqualTo("Ferme du Massif Central");
            assertThat(despatch.partyRoleCode()).isNull();
            assertThat(despatch.identifier()).isNull(); // gap G6
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
            assertThat(carrier.identifier()).isEqualTo("UK-TR-123456"); // <- approvalNumber
            assertThat(carrier.urlId()).isEqualTo("https://refdata.tbc.defra.gov.uk/uk_transporter_authorisation");
            assertThat(carrier.partyTypeCode()).singleElement().satisfies(code -> {
                assertThat(code.value()).isEqualTo("COMMERCIAL"); // gap G9 raw passthrough
                assertThat(code.urlId()).isEqualTo("https://traces-codelists.ec.europa.eu/operator_activity_type");
            });
        }

        @Test
        void shouldMapRegionOfOriginToOriginCountrySubDivision() {
            TradeCountry originCountry = result.specifiedConsignment().originCountry();
            assertThat(originCountry.code().value()).isEqualTo("FR");
            assertThat(originCountry.subordinateTradeCountrySubDivision()).satisfies(region -> {
                assertThat(region.identifier()).isEqualTo("FR-75");
                assertThat(region.functionTypeCode().content()).isEqualTo("106"); // region of origin
                assertThat(region.urlId()).isNull();
            });
        }

        @Test
        void shouldMapCphNumberToFinalDestinationLocation() {
            LogisticsLocation destination = result.specifiedConsignment().finalDestinationLocation();
            assertThat(destination.identifier()).isEqualTo("CPH19876");
            assertThat(destination.urlId()).isEqualTo("https://refdata.tbc.defra.gov.uk/cph_number");
            // Nothing collected says which address belongs to the holding, so none is sent.
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
                assertThat(doc.typeCode()).isEqualTo("730"); // road consignment note, as ROAD_VEHICLE
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
            assertThat(movement.modeCode()).isEqualTo(3); // ROAD_VEHICLE
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

            assertThat(line.description()).containsExactly("Cow"); // commodity.name -> description
            assertThat(line.commonName()).isEqualTo("Cow"); // commodity.name -> commonName
            assertThat(line.scientificName()).isEqualTo("Bos taurus"); // species.text -> scientificName
            assertThat(line.typeCode()).isNull();
            assertThat(line.urlId()).isNull();
            assertThat(line.applicableClassification()).singleElement().satisfies(c -> {
                assertThat(c.systemId()).isEqualTo("CN");
                assertThat(c.classCode().value()).isEqualTo("01020000"); // gap G15
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
            assertThat(instances).hasSize(2); // one per animal
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
            assertThat(instances.getFirst().name()).isNull();              // a cow has no name
            assertThat(instances.getFirst().permanentLocation()).isNull(); // or permanent address
        }
    }

    @Nested
    class MinimalAndDefaults {

        private final GbnAgEventData result = mapper.toGbnAgEventData(
            NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-MIN001")
                .status(NotificationStatus.DRAFT)
                .notification(Notification.builder().build())
                .build(), null);

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
                .containsExactly("PURPOSE", "GOODS_CERTIFIED_AS"); // no INTERNAL_MARKET_PURPOSE
            assertThat(clauses).allSatisfy(c -> assertThat(c.content()).isNull());
        }
    }

    @Test
    void toGbnAgEventData_shouldReturnNull_whenNotificationNull() {
        assertThat(mapper.toGbnAgEventData(null, null)).isNull();
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

        Integer modeCode = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst().modeCode();

        assertThat(modeCode).isEqualTo(expectedCode);
    }

    @Test
    void shouldMapLogisticsPackageToNull_whenTotalNoOfPackagesNull() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-PKG001")
            .notification(Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .totalNoOfPackages(null)
                        .build()))
                    .build())
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.physicalReferencedLogisticsPackage()).isNull();
    }

    @Test
    void shouldMapIndividualTradeProductInstanceToNull_whenSpeciesAbsent() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SPC001")
            .notification(Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .species(null)
                        .build()))
                    .build())
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.individualTradeProductInstance()).isNull();
        assertThat(line.scientificName()).isNull();
    }

    @Test
    void shouldMapApplicableClassificationToNull_whenTypeOfCommodityAbsent() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-CN001")
            .notification(Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity(null)
                        .build()))
                    .build())
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.applicableClassification()).isNull();
    }

    @Test
    void shouldMapScientificNameToNull_whenSpeciesEmpty() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SPC004")
            .notification(Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .species(List.of())
                        .build()))
                    .build())
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.scientificName()).isNull();
    }

    @Test
    void shouldMapCommonNameToNull_whenCommodityNameAbsent() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-CMD001")
            .notification(Notification.builder()
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .build()))
                    .build())
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notificationAggregate, 1)
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
                .commodity(Commodity.builder()
                    .name("Cow")
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("Domestic")
                        .totalNoOfAnimals(15)
                        .totalNoOfPackages(3)
                        .species(List.of(
                            Species.builder().value("1148346").text("Bos taurus")
                                .noOfAnimals(12).noOfPackages(1).build(),
                            Species.builder().value("749313").text("Bubalus bubalis")
                                .noOfAnimals(3).noOfPackages(2).build()))
                        .build()))
                    .build())
                .build())
            .build();

        List<TradeLineItem> lines = mapper.toGbnAgEventData(notificationAggregate, 1)
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
                .satisfies(c -> assertThat(c.classCode().value()).isEqualTo("Domestic"));
            assertThat(line.commonName()).isEqualTo("Cow");
            assertThat(line.description()).containsExactly("Cow");
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
        assertThat(dog.name()).isNull(); // only horses are named
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

        assertThat(mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().transitTradeCountry()).isNull();
    }

    @Test
    void shouldOmitFinalDestinationLocationAndRegion_whenBlank() {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-BLK001")
            .notification(Notification.builder()
                .origin(new Origin("FR", "false", "Imports456_GB", " "))
                .cphNumber(" ")
                .build())
            .build();

        SpecifiedConsignment consignment = mapper.toGbnAgEventData(notificationAggregate, 1).specifiedConsignment();

        assertThat(consignment.finalDestinationLocation()).isNull();
        assertThat(consignment.originCountry().subordinateTradeCountrySubDivision()).isNull();
    }

    private List<ReferencedDocument> transportDocumentsFor(Transport transport) {
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-DOC001")
            .notification(Notification.builder().transport(transport).build())
            .build();
        return mapper.toGbnAgEventData(notificationAggregate, 1)
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
                .commodity(Commodity.builder()
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .species(List.of(species))
                        .build()))
                    .build())
                .build())
            .build();
    }

    private TradeLineItem firstLineOf(NotificationAggregate notificationAggregate) {
        return mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();
    }

    private static NotificationAggregate fullyPopulatedNotification() {
        return NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-7K8M2P")
            .status(NotificationStatus.SUBMITTED)
            .updated(LocalDateTime.of(2026, Month.MAY, 21, 10, 15, 0))
            .notification(Notification.builder()
                .origin(new Origin("FR", "true", "Imports456_GB", "FR-75"))
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
                .placeOfOrigin(party("Ferme du Massif Central", simpleAddress("Route de la Vallée 12", "FR")))
                .consignment(party("Animal and Plant Health Agency", simpleAddress("Woodham Lane", "GB")))
                .cphNumber("CPH19876")
                .commodity(Commodity.builder()
                    .name("Cow")
                    .commodityComplement(List.of(CommodityComplement.builder()
                        .typeOfCommodity("01020000")
                        .totalNoOfAnimals(20)
                        .totalNoOfPackages(1)
                        .species(List.of(Species.builder()
                            .value("1148346")
                            .text("Bos taurus")
                            .noOfAnimals(20)
                            .noOfPackages(1)
                            // The scalars repeat the first animal, as the frontend sends them.
                            .earTag("UK01234567890")
                            .passport("UK0123456700999")
                            .animalIdentifiers(List.of(
                                AnimalIdentifier.builder()
                                    .earTag("UK01234567890").passport("UK0123456700999").build(),
                                AnimalIdentifier.builder()
                                    .earTag("UK01234567891").passport("UK0123456700998").build()))
                            .build()))
                        .build()))
                    .build())
                .transport(Transport.builder()
                    .portOfEntry("GBDVR")
                    .arrivalDate(LocalDate.of(2026, Month.MAY, 6))
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
    void shouldEmitNullNameAndPostalAddress_whenConsignorIsUnresolvedReference() {
        // TradeParty.from does not resolve address-book references; NotificationService must call
        // ConsignmentPartyResolver.validatePartiesAtSubmit before appendEvent, or GBNAG receives nulls.
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-REFMAP")
            .notification(Notification.builder()
                .consignor(NotificationTestData.reference("665f1c2ab3e4d51a2c9d0e77"))
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().consignorParty();

        assertThat(consignor.name()).isNull();
        assertThat(consignor.postalAddress()).isNull();
    }

    @Test
    void shouldMapResolvedReferencedParty_toConsignorNameAndPostalAddress() {
        // Shape after ConsignmentPartyResolver resolution: addressId retained with filled details.
        NotificationAggregate notificationAggregate = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-REFRES")
            .notification(Notification.builder()
                .consignor(ConsignmentParty.builder()
                    .addressId("665f1c2ab3e4d51a2c9d0e77")
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

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1)
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
                    .addressId("665f1c2ab3e4d51a2c9d0e77")
                    .name("Astra Rosales")
                    .email("astra@example.com")
                    .phone("01632 960111")
                    .address(simpleAddress("43 East Hague Extension", "CH"))
                    .build())
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1)
            .specifiedConsignment().consignorParty();

        assertThat(consignor.definedContact()).singleElement().satisfies(contact -> {
            assertThat(contact.emailURIUniversalCommunication()).isEqualTo("astra@example.com");
            assertThat(contact.telephoneUniversalCommunication()).isEqualTo("01632 960111");
            // The address book has no contact person — the name it holds is the party's own.
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

        TradeParty consignor = mapper.toGbnAgEventData(notificationAggregate, 1)
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
}
