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
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;
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

        private final GbnAgEventData result = mapper.toGbnAgEventData(fullyPopulatedNotification());

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
        void shouldMapOriginCountryWithoutRegion() {
            TradeCountry originCountry = result.specifiedConsignment().originCountry();
            assertThat(originCountry.code().value()).isEqualTo("FR");
            assertThat(originCountry.subordinateTradeCountrySubDivision()).isNull(); // gap G10
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

            // description is a required gbnAgTradeLineItem field; commodity.name -> description
            // deferred/unmapped pending the A3 team decision (schema anomaly A3)
            assertThat(line.description()).isNull();
            assertThat(line.commonName()).isNull();
            assertThat(line.scientificName()).isNull(); // gap G18
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
        void shouldReshapeSpeciesEarTagAndPassportIntoOneProductInstance() {
            TradeLineItem line = result.specifiedConsignment()
                .includedConsignmentItem().getFirst().includedTradeLineItem().getFirst();
            List<TradeProductInstance> instances = line.individualTradeProductInstance();
            assertThat(instances).hasSize(1); // gap G19 — one per species line, not per animal
            assertThat(instances.getFirst().identifier()).satisfiesExactly(
                earTag -> {
                    assertThat(earTag.typeCode()).isEqualTo("EAR_TAG");
                    assertThat(earTag.content()).isEqualTo("UK01234567890");
                },
                passport -> {
                    assertThat(passport.typeCode()).isEqualTo("PASSPORT");
                    assertThat(passport.content()).isEqualTo("UK0123456700999");
                });
            assertThat(instances.getFirst().name()).isNull();              // gap G20
            assertThat(instances.getFirst().permanentLocation()).isNull(); // gap G20
        }

        @Test
        void shouldNotYetSurfaceSourceFieldsLackingAGbnAgSlot() {
            // fullyPopulatedNotification() sets transport.transportDocumentReference, cphNumber
            // and consignment, but none has a live GBN-AG output slot yet. Kept visible here so
            // these assertions flip when the mappings land.

            // transport.transportDocumentReference -> mainCarriage movement's
            // transportContractRelatedReferencedDocument, currently dropped (candidate anomaly B1).
            LogisticsTransportMovement movement =
                result.specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst();
            assertThat(movement.transportContractRelatedReferencedDocument()).isNull();

            // cphNumber -> finalDestinationLocation.identifier (candidate anomaly B4): the
            // finalDestinationLocation slot is absent from SpecifiedConsignment, so cphNumber is
            // dropped entirely — no reachable output field carries it today.

            // consignment (competent-authority Operator): no GBN-AG party slot exists, so it is
            // dropped entirely; its nearest observable output, exchangedDocument().issuer(), is
            // already asserted null as gap G1.
        }
    }

    @Nested
    class MinimalAndDefaults {

        private final GbnAgEventData result = mapper.toGbnAgEventData(
            NotificationAggregate.builder()
                .referenceNumber("GBN-AG-26-MIN001")
                .status(NotificationStatus.DRAFT)
                .build());

        @Test
        void shouldStillSetConstantsAndIdentifier() {
            assertThat(result.model()).isEqualTo("defra/certificate-internal/1");
            assertThat(result.type()).isEqualTo("gbn-ag");
            assertThat(result.exchangedDocument().identifier()).isEqualTo("GBN-AG-26-MIN001");
            assertThat(result.exchangedDocument().notificationStatusCode()).isEqualTo("DRAFT");
            assertThat(result.exchangedDocument().versionId()).isEqualTo(1);
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
        assertThat(mapper.toGbnAgEventData(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"VESSEL,1", "RAILWAY,2", "ROAD_VEHICLE,3", "AIRPLANE,4"})
    void shouldMapMeansOfTransportToUnRec19ModeCode(MeansOfTransport means, int expectedCode) {
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-MODE01")
            .transport(Transport.builder().meansOfTransport(means).build())
            .build();

        Integer modeCode = mapper.toGbnAgEventData(notification)
            .specifiedConsignment().mainCarriageLogisticsTransportMovement().getFirst().modeCode();

        assertThat(modeCode).isEqualTo(expectedCode);
    }

    @Test
    void shouldMapLogisticsPackageToNull_whenTotalNoOfPackagesNull() {
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-PKG001")
            .commodity(Commodity.builder()
                .commodityComplement(List.of(CommodityComplement.builder()
                    .typeOfCommodity("01020000")
                    .totalNoOfPackages(null)
                    .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notification)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.physicalReferencedLogisticsPackage()).isNull();
    }

    @Test
    void shouldMapIndividualTradeProductInstanceToNull_whenSpeciesAbsent() {
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-SPC001")
            .commodity(Commodity.builder()
                .commodityComplement(List.of(CommodityComplement.builder()
                    .typeOfCommodity("01020000")
                    .species(null)
                    .build()))
                .build())
            .build();

        TradeLineItem line = mapper.toGbnAgEventData(notification)
            .specifiedConsignment().includedConsignmentItem().getFirst()
            .includedTradeLineItem().getFirst();

        assertThat(line.individualTradeProductInstance()).isNull();
    }

    private static NotificationAggregate fullyPopulatedNotification() {
        return NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-7K8M2P")
            .status(NotificationStatus.SUBMITTED)
            .updated(LocalDateTime.of(2026, Month.MAY, 21, 10, 15, 0))
            .origin(new Origin("FR", "true", "Imports456_GB"))
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
                .name("Live bovine animals")
                .commodityComplement(List.of(CommodityComplement.builder()
                    .typeOfCommodity("01020000")
                    .totalNoOfAnimals(20)
                    .totalNoOfPackages(1)
                    .species(List.of(Species.builder()
                        .value("BOV")
                        .text("Cattle")
                        .earTag("UK01234567890")
                        .passport("UK0123456700999")
                        .build()))
                    .build()))
                .build())
            .transport(Transport.builder()
                .portOfEntry("GBDVR")
                .arrivalDate(LocalDate.of(2026, Month.MAY, 6))
                .meansOfTransport(MeansOfTransport.ROAD_VEHICLE)
                .transportIdentification("AB-1234")
                .transportDocumentReference("BOL-2026-884721")
                .transporter(Transporter.builder()
                    .name("Acme Transport Ltd")
                    .address(simpleAddress("1 Haulage Way", "GB"))
                    .approvalNumber("UK-TR-123456")
                    .type("COMMERCIAL")
                    .build())
                .build())
            .build();
    }

    @Test
    void shouldEmitNullNameAndPostalAddress_whenConsignorIsUnresolvedReference() {
        // TradeParty.from does not resolve address-book references; NotificationService must call
        // ConsignmentPartyResolver.resolveForSubmission before appendEvent, or GBNAG receives nulls.
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-REFMAP")
            .consignor(NotificationTestData.reference("665f1c2ab3e4d51a2c9d0e77"))
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notification)
            .specifiedConsignment().consignorParty();

        assertThat(consignor.name()).isNull();
        assertThat(consignor.postalAddress()).isNull();
    }

    @Test
    void shouldMapResolvedReferencedParty_toConsignorNameAndPostalAddress() {
        // Shape after ConsignmentPartyResolver resolution: addressId retained with filled details.
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-REFRES")
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
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notification)
            .specifiedConsignment().consignorParty();

        assertThat(consignor.name()).isEqualTo("Astra Rosales");
        assertThat(consignor.postalAddress().lineOne()).isEqualTo("43 East Hague Extension");
        assertThat(consignor.postalAddress().cityName()).isEqualTo("Vernier");
        assertThat(consignor.postalAddress().countryId()).isEqualTo("CH");
    }

    @Test
    void shouldMapPartyEmailAndPhoneToDefinedContact() {
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-CONTACT")
            .consignor(ConsignmentParty.builder()
                .addressId("665f1c2ab3e4d51a2c9d0e77")
                .name("Astra Rosales")
                .email("astra@example.com")
                .phone("01632 960111")
                .address(simpleAddress("43 East Hague Extension", "CH"))
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notification)
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
        NotificationAggregate notification = NotificationAggregate.builder()
            .referenceNumber("GBN-AG-26-PHONLY")
            .consignor(ConsignmentParty.builder()
                .name("Astra Rosales")
                .phone("01632 960111")
                .address(simpleAddress("43 East Hague Extension", "CH"))
                .build())
            .build();

        TradeParty consignor = mapper.toGbnAgEventData(notification)
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
