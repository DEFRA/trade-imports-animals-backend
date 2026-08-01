package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.AMEND;
import static uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus.SUBMITTED;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.DocumentFile;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocument;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentDto;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentListResponse;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotification;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationDto;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsOrigin;
import uk.gov.defra.trade.imports.plantproducts.notification.StatusChangeRequest;

class PlantProductsAccompanyingDocumentIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/plant-products/notifications";
    private static final String UNKNOWN_REFERENCE = "GBN-PP-00-000000";

    @Autowired
    private PlantProductsNotificationRepository notificationRepository;

    @Autowired
    private PlantProductsAccompanyingDocumentRepository documentRepository;

    @BeforeEach
    void setUp() {
        documentRepository.deleteAll();
        notificationRepository.deleteAll();
    }

    @Test
    void list_shouldReturnWrappedEmptyDocumentsForFreshNotification() {
        // Given
        String referenceNumber = createParentNotification();

        // When
        PlantProductsAccompanyingDocumentListResponse response = listDocuments(referenceNumber);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.documents()).isEmpty();
        webClient("NoAuth")
            .get()
            .uri(documentBasePath(referenceNumber))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.documents").isArray();
    }

    @Test
    void getAndPost_shouldReturnNotFoundForUnknownNotification() {
        // When / Then - list
        webClient("NoAuth")
            .get()
            .uri(documentBasePath(UNKNOWN_REFERENCE))
            .exchange()
            .expectStatus().isNotFound();

        // When / Then - create
        webClient("NoAuth")
            .post()
            .uri(documentBasePath(UNKNOWN_REFERENCE))
            .bodyValue(documentDto("UNKNOWN", "unknown-file"))
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void postAndList_shouldCreateDocumentWithPathDerivedForeignKey() {
        // Given
        String referenceNumber = createParentNotification();
        PlantProductsAccompanyingDocumentDto request = documentDto("CREATE", "create-file");

        // When
        EntityExchangeResult<PlantProductsAccompanyingDocumentDto> result = webClient("NoAuth")
            .post()
            .uri(documentBasePath(referenceNumber))
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsAccompanyingDocumentDto.class)
            .returnResult();

        // Then
        PlantProductsAccompanyingDocumentDto created = result.getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotBlank();
        assertThat(created.documentType()).isEqualTo(request.documentType());
        assertThat(created.documentReference()).isEqualTo(request.documentReference());
        assertThat(created.issueDate()).isEqualTo(request.issueDate());
        assertThat(created.files()).isEqualTo(request.files());
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
            .endsWith(documentBasePath(referenceNumber) + "/" + created.id());

        PlantProductsAccompanyingDocument persisted = documentRepository.findById(created.id())
            .orElseThrow();
        assertThat(persisted.getNotificationReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(persisted.getCreated()).isNotNull();
        assertThat(persisted.getUpdated()).isNotNull();
        assertThat(listDocuments(referenceNumber).documents())
            .singleElement()
            .isEqualTo(created);
    }

    @Test
    void put_shouldReplaceDocumentAndPersistChangedFields() {
        // Given
        String referenceNumber = createParentNotification();
        PlantProductsAccompanyingDocumentDto created = createDocument(
            referenceNumber, documentDto("ORIGINAL", "original-file"));
        PlantProductsAccompanyingDocumentDto replacement =
            documentDto("REPLACED", "replacement-file");

        // When
        PlantProductsAccompanyingDocumentDto response = webClient("NoAuth")
            .put()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", created.id())
            .bodyValue(replacement)
            .exchange()
            .expectStatus().isOk()
            .expectBody(PlantProductsAccompanyingDocumentDto.class)
            .returnResult()
            .getResponseBody();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(created.id());
        assertThat(response.documentType()).isEqualTo(replacement.documentType());
        assertThat(response.documentReference()).isEqualTo(replacement.documentReference());
        assertThat(response.issueDate()).isEqualTo(replacement.issueDate());
        assertThat(response.files()).isEqualTo(replacement.files());
        PlantProductsAccompanyingDocument persisted = documentRepository.findById(created.id())
            .orElseThrow();
        assertThat(persisted.getNotificationReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(persisted.getDocumentReference()).isEqualTo(replacement.documentReference());
        assertThat(persisted.getFiles()).isEqualTo(replacement.files());
    }

    @Test
    void putAndDelete_shouldReturnNotFoundForDocumentOwnedByAnotherNotification() {
        // Given
        String ownerReference = createParentNotification();
        String otherReference = createParentNotification();
        PlantProductsAccompanyingDocumentDto created = createDocument(
            ownerReference, documentDto("OWNED", "owned-file"));

        // When / Then - replace under a different parent
        webClient("NoAuth")
            .put()
            .uri(documentBasePath(otherReference) + "/{documentId}", created.id())
            .bodyValue(documentDto("CROSS", "cross-file"))
            .exchange()
            .expectStatus().isNotFound();

        // When / Then - delete under a different parent
        webClient("NoAuth")
            .delete()
            .uri(documentBasePath(otherReference) + "/{documentId}", created.id())
            .exchange()
            .expectStatus().isNotFound();
        assertThat(documentRepository.findById(created.id())).isPresent();
    }

    @Test
    void delete_shouldRemoveDocumentAndReturnNotFoundForUnknownId() {
        // Given
        String referenceNumber = createParentNotification();
        PlantProductsAccompanyingDocumentDto created = createDocument(
            referenceNumber, documentDto("DELETE", "delete-file"));

        // When / Then - delete existing document
        webClient("NoAuth")
            .delete()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", created.id())
            .exchange()
            .expectStatus().isNoContent();
        assertThat(listDocuments(referenceNumber).documents()).isEmpty();

        // When / Then - delete unknown document
        webClient("NoAuth")
            .delete()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", "unknown-document-id")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void writes_shouldBeRejectedWhileSubmittedAndSucceedAgainWhileAmending() {
        // Given
        String referenceNumber = createParentNotification();
        PlantProductsAccompanyingDocumentDto existing = createDocument(
            referenceNumber, documentDto("EXISTING", "existing-file"));
        changeStatus(referenceNumber, SUBMITTED);

        // When / Then - every document write is rejected while SUBMITTED
        List<PlantProductsAccompanyingDocument> beforeRejectedPost = documentRepository.findAll();
        webClient("NoAuth").post()
            .uri(documentBasePath(referenceNumber))
            .bodyValue(documentDto("BLOCKED-POST", "blocked-post-file"))
            .exchange().expectStatus().isBadRequest();
        assertDocumentsUnchanged(beforeRejectedPost);

        List<PlantProductsAccompanyingDocument> beforeRejectedPut = documentRepository.findAll();
        webClient("NoAuth").put()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", existing.id())
            .bodyValue(documentDto("BLOCKED-PUT", "blocked-put-file"))
            .exchange().expectStatus().isBadRequest();
        assertDocumentsUnchanged(beforeRejectedPut);

        List<PlantProductsAccompanyingDocument> beforeRejectedDelete = documentRepository.findAll();
        webClient("NoAuth").delete()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", existing.id())
            .exchange().expectStatus().isBadRequest();
        assertDocumentsUnchanged(beforeRejectedDelete);

        // Given - the notification is now writable in AMEND
        changeStatus(referenceNumber, AMEND);

        // When / Then - create, replace and delete all succeed again
        createDocument(referenceNumber, documentDto("AMEND-POST", "amend-post-file"));
        webClient("NoAuth").put()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", existing.id())
            .bodyValue(documentDto("AMEND-PUT", "amend-put-file"))
            .exchange().expectStatus().isOk();
        webClient("NoAuth").delete()
            .uri(documentBasePath(referenceNumber) + "/{documentId}", existing.id())
            .exchange().expectStatus().isNoContent();
    }

    private String createParentNotification() {
        PlantProductsNotificationDto dto = PlantProductsNotificationDto.builder()
            .origin(PlantProductsOrigin.builder()
                .countryCode("NL")
                .countryOfConsignmentCode("BE")
                .internalReference("DOCUMENT-PARENT")
                .build())
            .build();
        PlantProductsNotification created = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsNotification.class)
            .returnResult()
            .getResponseBody();
        assertThat(created).isNotNull();
        return created.getReferenceNumber();
    }

    private PlantProductsAccompanyingDocumentDto createDocument(
        String referenceNumber, PlantProductsAccompanyingDocumentDto dto) {
        return webClient("NoAuth")
            .post()
            .uri(documentBasePath(referenceNumber))
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(PlantProductsAccompanyingDocumentDto.class)
            .returnResult()
            .getResponseBody();
    }

    private PlantProductsAccompanyingDocumentListResponse listDocuments(String referenceNumber) {
        return webClient("NoAuth")
            .get()
            .uri(documentBasePath(referenceNumber))
            .exchange()
            .expectStatus().isOk()
            .expectBody(PlantProductsAccompanyingDocumentListResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private void assertDocumentsUnchanged(
        List<PlantProductsAccompanyingDocument> expectedDocuments) {
        List<PlantProductsAccompanyingDocument> actualDocuments = documentRepository.findAll();
        assertThat(actualDocuments).hasSize(expectedDocuments.size());
        assertThat(actualDocuments)
            .usingRecursiveComparison()
            .ignoringCollectionOrder()
            .isEqualTo(expectedDocuments);
    }

    private void changeStatus(
        String referenceNumber,
        uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus status) {
        webClient("NoAuth")
            .put()
            .uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}/status", referenceNumber)
            .bodyValue(new StatusChangeRequest(status, null))
            .exchange()
            .expectStatus().isOk();
    }

    private static String documentBasePath(String referenceNumber) {
        return NOTIFICATION_ENDPOINT + "/" + referenceNumber + "/accompanying-documents";
    }

    private static PlantProductsAccompanyingDocumentDto documentDto(
        String suffix, String fileId) {
        return new PlantProductsAccompanyingDocumentDto(
            null,
            "PHYTOSANITARY_CERTIFICATE_" + suffix,
            "PHYTO-" + suffix + "-001",
            LocalDate.of(2026, 3, suffix.length() + 1),
            List.of(DocumentFile.builder()
                .fileId(fileId)
                .filename(fileId + ".pdf")
                .build()));
    }
}
