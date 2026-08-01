package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.AMEND;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.DELETED;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.DRAFT;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.SUBMITTED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.DocumentFile;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocument;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.BillingAddress;
import uk.gov.defra.trade.imports.plantproducts.notification.CommodityInputMethod;
import uk.gov.defra.trade.imports.plantproducts.notification.CommodityLine;
import uk.gov.defra.trade.imports.plantproducts.notification.CommonTransitConvention;
import uk.gov.defra.trade.imports.plantproducts.notification.Declaration;
import uk.gov.defra.trade.imports.plantproducts.notification.FinishedOrPropagated;
import uk.gov.defra.trade.imports.plantproducts.notification.GoodsMovementServices;
import uk.gov.defra.trade.imports.plantproducts.notification.GrossVolumeUnit;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsAdditionalDetails;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsAddress;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsBilling;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsCommodity;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsContact;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsMeansOfTransport;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotification;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationBase;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationDto;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationPageResponse;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationResponse;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsOperator;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsOrigin;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsReferenceNumberGenerator;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsTransport;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantSpecies;
import uk.gov.defra.trade.imports.plantproducts.notification.ReasonForImport;
import uk.gov.defra.trade.imports.plantproducts.notification.SpeciesVariety;
import uk.gov.defra.trade.imports.plantproducts.notification.StatusChangeRequest;
import uk.gov.defra.trade.imports.plantproducts.notification.TransportContainer;
import uk.gov.defra.trade.imports.plantproducts.notification.VarietyClass;

class PlantProductsNotificationIT extends IntegrationBase {

    private static final String ENDPOINT = "/plant-products/notifications";
    private static final String REF_FORMAT_REGEX =
        PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;
    private static final String NONEXISTENT_REF = "GBN-PP-00-000000";

    @Autowired
    private PlantProductsNotificationRepository notificationRepository;

    @Autowired
    private PlantProductsAccompanyingDocumentRepository accompanyingDocumentRepository;

    @BeforeEach
    void setUp() {
        accompanyingDocumentRepository.deleteAll();
        notificationRepository.deleteAll();
    }

    @Test
    void post_shouldCreateAndRoundTripEveryContentField() {
        // Given
        PlantProductsNotificationDto dto = fullNotificationDto();

        // When
        EntityExchangeResult<PlantProductsNotification> result = webClient("NoAuth")
            .post()
            .uri(ENDPOINT)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsNotification.class)
            .returnResult();

        // Then
        PlantProductsNotification created = result.getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
            .endsWith(ENDPOINT + "/" + created.getReferenceNumber());
        assertThat(created.getStatus()).isEqualTo(DRAFT);
        assertThat(created.getChedType()).isEqualTo("CHEDPP");
        assertThat(created.getOwnership().getAssignedOrganisationId()).isEqualTo("stub-org");
        assertThat(created.getOwnership().getAssignedOrganisationName())
            .isEqualTo("Stubbed organisation");
        assertThat(created.getCreated()).isNotNull();
        assertThat(created.getUpdated()).isNotNull();

        PlantProductsNotificationResponse reloaded = get(created.getReferenceNumber());
        assertContentFields(dto, reloaded);
        assertThat(reloaded.accompanyingDocuments()).isEmpty();
        webClient("NoAuth")
            .get()
            .uri(ENDPOINT + "/{referenceNumber}", created.getReferenceNumber())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.submittedBaseline").doesNotExist()
            .jsonPath("$.expireAt").doesNotExist();
    }

    @Test
    void post_shouldRejectBodySuppliedReferenceNumber() {
        // Given
        PlantProductsNotificationDto dto = fullNotificationDto();
        dto.setReferenceNumber(NONEXISTENT_REF);

        // When / Then
        webClient("NoAuth")
            .post()
            .uri(ENDPOINT)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void put_shouldReplaceExistingNotificationAndUpsertUnknownReference() {
        // Given
        String existingReference = createFullNotification().getReferenceNumber();
        PlantProductsNotificationDto replacement = fullNotificationDto();
        replacement.setReferenceNumber(existingReference);
        replacement.getOrigin().setInternalReference("REPLACED-INTERNAL-REFERENCE");

        // When / Then - replace
        webClient("NoAuth")
            .put()
            .uri(ENDPOINT + "/{referenceNumber}", existingReference)
            .bodyValue(replacement)
            .exchange()
            .expectStatus().isOk();
        assertContentFields(replacement, get(existingReference));

        // Given - an unknown, well-formed reference
        PlantProductsNotificationDto upsert = fullNotificationDto();
        upsert.setReferenceNumber(NONEXISTENT_REF);

        // When / Then - upsert
        EntityExchangeResult<PlantProductsNotification> result = webClient("NoAuth")
            .put()
            .uri(ENDPOINT + "/{referenceNumber}", NONEXISTENT_REF)
            .bodyValue(upsert)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsNotification.class)
            .returnResult();
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
            .endsWith(ENDPOINT + "/" + NONEXISTENT_REF);
        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getResponseBody().getReferenceNumber()).isEqualTo(NONEXISTENT_REF);
        assertContentFields(upsert, get(NONEXISTENT_REF));
    }

    @Test
    void put_shouldRejectReferenceMismatchAndSubmittedNotification() {
        // Given
        String referenceNumber = createFullNotification().getReferenceNumber();
        PlantProductsNotificationDto mismatched = fullNotificationDto();
        mismatched.setReferenceNumber(NONEXISTENT_REF);

        // When / Then - mismatched body and path references
        webClient("NoAuth")
            .put()
            .uri(ENDPOINT + "/{referenceNumber}", referenceNumber)
            .bodyValue(mismatched)
            .exchange()
            .expectStatus().isBadRequest();

        // Given - a submitted notification
        changeStatus(referenceNumber, SUBMITTED, null).expectStatus().isOk();
        PlantProductsNotificationDto matching = fullNotificationDto();
        matching.setReferenceNumber(referenceNumber);

        // When / Then - submitted content is not writable
        webClient("NoAuth")
            .put()
            .uri(ENDPOINT + "/{referenceNumber}", referenceNumber)
            .bodyValue(matching)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void get_shouldReturnNotFoundForUnknownReferenceAndBadRequestForMalformedReference() {
        // When / Then - unknown, well-formed reference
        webClient("NoAuth")
            .get()
            .uri(ENDPOINT + "/{referenceNumber}", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound();

        // When / Then - malformed reference
        webClient("NoAuth")
            .get()
            .uri(ENDPOINT + "/{referenceNumber}", "not-a-plant-reference")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void findAll_shouldUseOneBasedMetadataFilterExactlyAndHideDeletedRows() {
        // Given
        String firstReference = createFullNotification().getReferenceNumber();
        String matchingReference = createFullNotification().getReferenceNumber();
        String deletedReference = createFullNotification().getReferenceNumber();
        changeStatus(deletedReference, DELETED, null).expectStatus().isOk();

        // When
        PlantProductsNotificationPageResponse page = findAll(1, "createdAt,asc", null);
        PlantProductsNotificationPageResponse filtered =
            findAll(1, null, matchingReference);

        // Then
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(25);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.content())
            .extracting(PlantProductsNotificationDto::getReferenceNumber)
            .containsExactlyInAnyOrder(firstReference, matchingReference)
            .doesNotContain(deletedReference);
        assertThat(filtered.page()).isEqualTo(1);
        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.content())
            .singleElement()
            .extracting(PlantProductsNotificationDto::getReferenceNumber)
            .isEqualTo(matchingReference);
    }

    @Test
    void lifecycle_shouldSubmitAmendCancelAndCompleteAmendment() {
        // Given
        PlantProductsNotificationDto original = fullNotificationDto();
        String referenceNumber = create(original).getReferenceNumber();
        String originalInternalReference = original.getOrigin().getInternalReference();

        // When / Then - DRAFT -> SUBMITTED captures a repository-only baseline
        changeStatus(referenceNumber, SUBMITTED, null)
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("SUBMITTED")
            .jsonPath("$.submittedBaseline").doesNotExist();
        PlantProductsNotification submitted = notificationRepository
            .findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(submitted.getSubmittedBaseline()).isNotNull();
        assertThat(submitted.getSubmittedBaseline().getOrigin().getInternalReference())
            .isEqualTo(originalInternalReference);

        // When / Then - SUBMITTED -> AMEND, edit, then cancel restores pre-amend content
        changeStatus(referenceNumber, AMEND, null).expectStatus().isOk();
        replaceInternalReference(referenceNumber, "CANCELLED-AMENDMENT");
        changeStatus(referenceNumber, SUBMITTED, true).expectStatus().isOk();
        assertThat(get(referenceNumber).origin().getInternalReference())
            .isEqualTo(originalInternalReference);

        // When / Then - re-amend and submit without discard keeps the amended content
        changeStatus(referenceNumber, AMEND, null).expectStatus().isOk();
        replaceInternalReference(referenceNumber, "COMPLETED-AMENDMENT");
        changeStatus(referenceNumber, SUBMITTED, null).expectStatus().isOk();
        assertThat(get(referenceNumber).origin().getInternalReference())
            .isEqualTo("COMPLETED-AMENDMENT");
        PlantProductsNotification completed = notificationRepository
            .findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(completed.getSubmittedBaseline()).isNotNull();
        assertThat(completed.getSubmittedBaseline().getOrigin().getInternalReference())
            .isEqualTo("COMPLETED-AMENDMENT");
    }

    @Test
    void changeStatus_shouldRejectIllegalTransitionsAndInvalidDiscardFlag() {
        // Given
        String draftReference = createFullNotification().getReferenceNumber();

        // When / Then - DRAFT -> AMEND is illegal
        changeStatus(draftReference, AMEND, null).expectStatus().isBadRequest();

        // When / Then - discard is invalid on DRAFT -> SUBMITTED
        changeStatus(draftReference, SUBMITTED, true).expectStatus().isBadRequest();

        // Given - a submitted notification
        changeStatus(draftReference, SUBMITTED, null).expectStatus().isOk();

        // When / Then - SUBMITTED -> SUBMITTED is illegal
        changeStatus(draftReference, SUBMITTED, null).expectStatus().isBadRequest();
    }

    @Test
    void copy_shouldCreateDocumentlessDraftAndRejectInvalidSources() {
        // Given
        PlantProductsNotificationDto sourceDto = fullNotificationDto();
        String sourceReference = create(sourceDto).getReferenceNumber();
        accompanyingDocumentRepository.save(PlantProductsAccompanyingDocument.builder()
            .notificationReferenceNumber(sourceReference)
            .documentType("PHYTOSANITARY_CERTIFICATE")
            .documentReference("PHYTO-COPY-001")
            .issueDate(LocalDate.of(2026, 2, 3))
            .files(List.of(DocumentFile.builder()
                .fileId("copy-source-file")
                .filename("source.pdf")
                .build()))
            .build());
        changeStatus(sourceReference, SUBMITTED, null).expectStatus().isOk();

        // When
        EntityExchangeResult<PlantProductsNotification> copyResult = webClient("NoAuth")
            .post()
            .uri(ENDPOINT + "/{referenceNumber}/copies", sourceReference)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsNotification.class)
            .returnResult();

        // Then
        PlantProductsNotification copy = copyResult.getResponseBody();
        assertThat(copy).isNotNull();
        assertThat(copy.getReferenceNumber()).matches(REF_FORMAT_REGEX).isNotEqualTo(sourceReference);
        assertThat(copyResult.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
            .endsWith(ENDPOINT + "/" + copy.getReferenceNumber());
        assertThat(copy.getStatus()).isEqualTo(DRAFT);
        assertThat(copy.getDeclaration()).isNull();
        sourceDto.setDeclaration(null);
        assertContentFields(sourceDto, copy);
        assertThat(get(copy.getReferenceNumber()).accompanyingDocuments()).isEmpty();
        assertThat(notificationRepository.findByReferenceNumber(sourceReference).orElseThrow().getStatus())
            .isEqualTo(SUBMITTED);
        assertThat(accompanyingDocumentRepository.findByNotificationReferenceNumber(sourceReference))
            .hasSize(1);

        // Given - a DRAFT source
        String draftReference = createFullNotification().getReferenceNumber();

        // When / Then - invalid and unknown sources
        webClient("NoAuth").post()
            .uri(ENDPOINT + "/{referenceNumber}/copies", draftReference)
            .exchange().expectStatus().isBadRequest();
        webClient("NoAuth").post()
            .uri(ENDPOINT + "/{referenceNumber}/copies", NONEXISTENT_REF)
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void softDelete_shouldHandleEveryActiveStatusIdempotentlyAndHideRows() {
        // Given
        String draftReference = createFullNotification().getReferenceNumber();
        String submittedReference = createFullNotification().getReferenceNumber();
        String amendReference = createFullNotification().getReferenceNumber();
        changeStatus(submittedReference, SUBMITTED, null).expectStatus().isOk();
        changeStatus(amendReference, SUBMITTED, null).expectStatus().isOk();
        changeStatus(amendReference, AMEND, null).expectStatus().isOk();

        // When / Then - DRAFT/SUBMITTED/AMEND -> DELETED
        changeStatus(draftReference, DELETED, null).expectStatus().isOk();
        changeStatus(submittedReference, DELETED, null).expectStatus().isOk();
        changeStatus(amendReference, DELETED, null).expectStatus().isOk();

        // When / Then - an already-deleted row is idempotent and all are hidden
        changeStatus(draftReference, DELETED, null)
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("DELETED");
        PlantProductsNotificationPageResponse page = findAll(1, null, null);
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void changeStatus_shouldReturnNotFoundForUnknownReference() {
        // When / Then - unknown reference
        changeStatus(NONEXISTENT_REF, SUBMITTED, null).expectStatus().isNotFound();
    }

    private PlantProductsNotification createFullNotification() {
        return create(fullNotificationDto());
    }

    private PlantProductsNotification create(PlantProductsNotificationDto dto) {
        return webClient("NoAuth")
            .post()
            .uri(ENDPOINT)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsNotification.class)
            .returnResult()
            .getResponseBody();
    }

    private PlantProductsNotificationResponse get(String referenceNumber) {
        return webClient("NoAuth")
            .get()
            .uri(ENDPOINT + "/{referenceNumber}", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(PlantProductsNotificationResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private PlantProductsNotificationPageResponse findAll(
        int page, String sort, String referenceNumber) {
        return webClient("NoAuth")
            .get()
            .uri(uriBuilder -> {
                uriBuilder.path(ENDPOINT).queryParam("page", page);
                if (sort != null) {
                    uriBuilder.queryParam("sort", sort);
                }
                if (referenceNumber != null) {
                    uriBuilder.queryParam("referenceNumber", referenceNumber);
                }
                return uriBuilder.build();
            })
            .exchange()
            .expectStatus().isOk()
            .expectBody(PlantProductsNotificationPageResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private WebTestClient.ResponseSpec changeStatus(
        String referenceNumber,
        uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus status,
        Boolean discardChanges) {
        return webClient("NoAuth")
            .put()
            .uri(ENDPOINT + "/{referenceNumber}/status", referenceNumber)
            .bodyValue(new StatusChangeRequest(status, discardChanges))
            .exchange();
    }

    private void replaceInternalReference(String referenceNumber, String internalReference) {
        PlantProductsNotificationDto dto = fullNotificationDto();
        dto.setReferenceNumber(referenceNumber);
        dto.getOrigin().setInternalReference(internalReference);
        webClient("NoAuth")
            .put()
            .uri(ENDPOINT + "/{referenceNumber}", referenceNumber)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isOk();
    }

    private static void assertContentFields(
        PlantProductsNotificationDto expected, PlantProductsNotificationBase actual) {
        assertThat(actual.getOrigin()).isEqualTo(expected.getOrigin());
        assertThat(actual.getReasonForImport()).isEqualTo(expected.getReasonForImport());
        assertThat(actual.getCommodity()).isEqualTo(expected.getCommodity());
        assertThat(actual.getAdditionalDetails()).isEqualTo(expected.getAdditionalDetails());
        assertThat(actual.getConsignor()).isEqualTo(expected.getConsignor());
        assertThat(actual.getConsignee()).isEqualTo(expected.getConsignee());
        assertThat(actual.getImporter()).isEqualTo(expected.getImporter());
        assertThat(actual.getDestination()).isEqualTo(expected.getDestination());
        assertThat(actual.getPacker()).isEqualTo(expected.getPacker());
        assertThat(actual.getResponsiblePerson()).isEqualTo(expected.getResponsiblePerson());
        assertThat(actual.getNominatedContacts()).isEqualTo(expected.getNominatedContacts());
        assertThat(actual.getTransport()).isEqualTo(expected.getTransport());
        assertThat(actual.getGoodsMovementServices()).isEqualTo(expected.getGoodsMovementServices());
        assertThat(actual.getIsCuc()).isEqualTo(expected.getIsCuc());
        assertThat(actual.getBilling()).isEqualTo(expected.getBilling());
        assertThat(actual.getDeclaration()).isEqualTo(expected.getDeclaration());
    }

    private static void assertContentFields(
        PlantProductsNotificationDto expected, PlantProductsNotificationResponse actual) {
        assertThat(actual.origin()).isEqualTo(expected.getOrigin());
        assertThat(actual.reasonForImport()).isEqualTo(expected.getReasonForImport());
        assertThat(actual.commodity()).isEqualTo(expected.getCommodity());
        assertThat(actual.additionalDetails()).isEqualTo(expected.getAdditionalDetails());
        assertThat(actual.consignor()).isEqualTo(expected.getConsignor());
        assertThat(actual.consignee()).isEqualTo(expected.getConsignee());
        assertThat(actual.importer()).isEqualTo(expected.getImporter());
        assertThat(actual.destination()).isEqualTo(expected.getDestination());
        assertThat(actual.packer()).isEqualTo(expected.getPacker());
        assertThat(actual.responsiblePerson()).isEqualTo(expected.getResponsiblePerson());
        assertThat(actual.nominatedContacts()).isEqualTo(expected.getNominatedContacts());
        assertThat(actual.transport()).isEqualTo(expected.getTransport());
        assertThat(actual.goodsMovementServices()).isEqualTo(expected.getGoodsMovementServices());
        assertThat(actual.isCuc()).isEqualTo(expected.getIsCuc());
        assertThat(actual.billing()).isEqualTo(expected.getBilling());
        assertThat(actual.declaration()).isEqualTo(expected.getDeclaration());
    }

    private static PlantProductsNotificationDto fullNotificationDto() {
        PlantSpecies species = PlantSpecies.builder()
            .eppoCode("SOLTU")
            .genusAndSpecies("Solanum tuberosum")
            .speciesId("species-potato-001")
            .varieties(List.of(SpeciesVariety.builder()
                .variety("King Edward")
                .varietyClass(VarietyClass.CLASS_I)
                .build()))
            .build();
        CommodityLine commodityLine = CommodityLine.builder()
            .uniqueComplementId("commodity-line-001")
            .commodityCode("070190")
            .commodityDescription("Fresh potatoes")
            .numberOfPackages(12)
            .packageType("BOX")
            .quantity(new BigDecimal("125.50"))
            .quantityType("KILOGRAMS")
            .netWeight(new BigDecimal("120.25"))
            .controlledAtmosphereContainer(true)
            .finishedOrPropagated(FinishedOrPropagated.PROPAGATED)
            .intendedForFinalUsers(false)
            .testAndTrial(true)
            .species(List.of(species))
            .build();
        return PlantProductsNotificationDto.builder()
            .origin(PlantProductsOrigin.builder()
                .countryCode("NL")
                .countryOfConsignmentCode("BE")
                .internalReference("IMPORTER-PP-001")
                .build())
            .reasonForImport(ReasonForImport.INTERNAL_MARKET)
            .commodity(PlantProductsCommodity.builder()
                .name("Plants for planting")
                .inputMethod(CommodityInputMethod.MANUAL)
                .commodityComplement(List.of(commodityLine))
                .build())
            .additionalDetails(PlantProductsAdditionalDetails.builder()
                .totalGrossWeight(new BigDecimal("140.75"))
                .grossVolume(new BigDecimal("18.25"))
                .grossVolumeUnit(GrossVolumeUnit.METRES_CUBED)
                .build())
            .consignor(operator("CONSIGNOR", "NL"))
            .consignee(operator("CONSIGNEE", "GB"))
            .importer(operator("IMPORTER", "GB"))
            .destination(operator("DESTINATION", "GB"))
            .packer(operator("PACKER", "BE"))
            .responsiblePerson(contact("Responsible Person", true))
            .nominatedContacts(List.of(
                contact("Nominated Contact One", false),
                contact("Nominated Contact Two", true)))
            .transport(PlantProductsTransport.builder()
                .borderControlPost("GBFXT1PP")
                .inspectionPremises("Felixstowe plant inspection facility")
                .meansOfTransport(PlantProductsMeansOfTransport.ROAD_VEHICLE)
                .transportIdentification("TRACTOR-PP-001")
                .transportDocumentReference("CMR-PP-001")
                .arrivalDate(LocalDate.of(2026, 4, 22))
                .arrivalTime("14:35")
                .usesContainers(true)
                .containers(List.of(TransportContainer.builder()
                    .containerNumber("MSCU1234567")
                    .sealNumber("SEAL-PP-001")
                    .officialSeal(true)
                    .build()))
                .build())
            .goodsMovementServices(GoodsMovementServices.builder()
                .commonTransitConvention(CommonTransitConvention.ADD_MRN_NOW)
                .movementReferenceNumber("26GB00000123456789")
                .usingGvms(true)
                .build())
            .isCuc(true)
            .billing(PlantProductsBilling.builder()
                .address(BillingAddress.builder()
                    .addressLine1("1 Billing Street")
                    .addressLine2("Billing Estate")
                    .addressLine3("Billing Building")
                    .addressLine4("Billing District")
                    .cityOrTown("London")
                    .county("Greater London")
                    .postalCode("SW1A 1AA")
                    .build())
                .email("billing@example.com")
                .telephone("+44 20 7946 0999")
                .build())
            .declaration(Declaration.builder()
                .agreed(true)
                .declaredAt(LocalDateTime.of(2026, 4, 20, 9, 15))
                .build())
            .build();
    }

    private static PlantProductsOperator operator(String prefix, String country) {
        String lowerPrefix = prefix.toLowerCase();
        return PlantProductsOperator.builder()
            .operatorId(prefix + "-OPERATOR-ID")
            .name(prefix + " Trading Ltd")
            .telephone("+44 20 7000 " + prefix.length() + "001")
            .email(lowerPrefix + "@example.com")
            .address(PlantProductsAddress.builder()
                .addressLine1(prefix + " House")
                .addressLine2("1 " + prefix + " Street")
                .addressLine3(prefix + " Industrial Estate")
                .city(prefix + " City")
                .postcode("AB1 2CD")
                .country(country)
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
