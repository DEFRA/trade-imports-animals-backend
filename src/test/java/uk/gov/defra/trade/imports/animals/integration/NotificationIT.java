package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.springframework.core.ParameterizedTypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationResponse;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Species;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

class NotificationIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String ADMIN_SECRET_HEADER = "Trade-Imports-Animals-Admin-Secret";
    private static final String VALID_ADMIN_SECRET = "test-admin-secret";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
        accompanyingDocumentRepository.deleteAll();
    }

    @Test
    void post_shouldMapAllFieldsToNotificationAndSave() {
        // Given
        Species species = NotificationTestData.species();
        CommodityComplement complement = new CommodityComplement("LIVE", 10, 5, List.of(species));
        Commodity commodity = Commodity.builder()
            .name("Live bovine animals")
            .commodityComplement(List.of(complement))
            .build();
        Transport transport = Transport.builder()
            .portOfEntry("GBFXT")
            .arrivalDate(LocalDate.of(2026, 4, 22))
            .build();
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(commodity)
            .reasonForImport("PERMANENT")
            .additionalDetails(new AdditionalDetails("HUMAN_CONSUMPTION", "true"))
            .cphNumber("22/123/4567")
            .transport(transport)
            .build();

        // When
        Notification created = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — verify response
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).matches("DRAFT\\.IMP\\.\\d{4}\\..+");
        assertThat(created.getReferenceNumber()).contains(created.getId());

        assertThat(created.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(created.getOrigin().getRequiresRegionCode()).isEqualTo("true");
        assertThat(created.getOrigin().getInternalReference()).isEqualTo("REF-001");

        assertThat(created.getCommodity().getName()).isEqualTo("Live bovine animals");
        CommodityComplement createdComplement = created.getCommodity().getCommodityComplement().getFirst();
        assertThat(createdComplement.getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(createdComplement.getTotalNoOfAnimals()).isEqualTo(10);
        assertThat(createdComplement.getTotalNoOfPackages()).isEqualTo(5);
        Species createdSpecies = createdComplement.getSpecies().getFirst();
        assertThat(createdSpecies.getValue()).isEqualTo("BOV");
        assertThat(createdSpecies.getText()).isEqualTo("Bovine");
        assertThat(createdSpecies.getNoOfAnimals()).isEqualTo(10);
        assertThat(createdSpecies.getNoOfPackages()).isEqualTo(5);
        assertThat(createdSpecies.getEarTag()).isEqualTo("UK01234567890");
        assertThat(createdSpecies.getPassport()).isEqualTo("UK0123456700999");

        assertThat(created.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(created.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(created.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
        assertThat(created.getCphNumber()).isEqualTo("22/123/4567");
        assertThat(created.getTransport().getPortOfEntry()).isEqualTo("GBFXT");
        assertThat(created.getTransport().getArrivalDate()).isEqualTo(LocalDate.of(2026, 4, 22));

        // Verify persisted — reload via API
        Notification persisted = findAllNotifications().getFirst();
        assertThat(persisted.getId()).isEqualTo(created.getId());
        assertThat(persisted.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(persisted.getOrigin().getRequiresRegionCode()).isEqualTo("true");
        assertThat(persisted.getOrigin().getInternalReference()).isEqualTo("REF-001");

        assertThat(persisted.getCommodity().getName()).isEqualTo("Live bovine animals");
        CommodityComplement persistedComplement = persisted.getCommodity().getCommodityComplement().getFirst();
        assertThat(persistedComplement.getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(persistedComplement.getTotalNoOfAnimals()).isEqualTo(10);
        assertThat(persistedComplement.getTotalNoOfPackages()).isEqualTo(5);
        Species persistedSpecies = persistedComplement.getSpecies().getFirst();
        assertThat(persistedSpecies.getValue()).isEqualTo("BOV");
        assertThat(persistedSpecies.getText()).isEqualTo("Bovine");
        assertThat(persistedSpecies.getNoOfAnimals()).isEqualTo(10);
        assertThat(persistedSpecies.getNoOfPackages()).isEqualTo(5);
        assertThat(persistedSpecies.getEarTag()).isEqualTo("UK01234567890");
        assertThat(persistedSpecies.getPassport()).isEqualTo("UK0123456700999");

        assertThat(persisted.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(persisted.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(persisted.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
        assertThat(persisted.getCphNumber()).isEqualTo("22/123/4567");
        assertThat(persisted.getTransport().getPortOfEntry()).isEqualTo("GBFXT");
        assertThat(persisted.getTransport().getArrivalDate()).isEqualTo(LocalDate.of(2026, 4, 22));
    }

    @Test
    void findAll_shouldReturnAllNotifications() {
        // Given - create multiple notifications
        NotificationDto notificationDto1 = createNotificationDto("GB", "Live cattle");
        NotificationDto notificationDto2 = createNotificationDto("IE", "Live sheep");
        NotificationDto notificationDto3 = createNotificationDto("FR", "Live pigs");

        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(notificationDto1).exchange();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(notificationDto2).exchange();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(notificationDto3).exchange();

        // When
        List<Notification> notifications = findAllNotifications();

        // Then
        assertThat(notifications).isNotNull().hasSize(3);
        assertThat(notifications)
            .extracting(n -> n.getOrigin().getCountryCode())
            .containsExactlyInAnyOrder("GB", "IE", "FR");
        assertThat(notifications)
            .extracting(Notification::getId)
            .allMatch(id -> id != null && !id.isEmpty());
        assertThat(notifications)
            .extracting(Notification::getReferenceNumber)
            .allMatch(ref -> ref != null && ref.startsWith("DRAFT.IMP."));
    }

    @Test
    void findAll_shouldReturnEmptyList_whenNoNotifications() {
        // When
        List<Notification> notifications = findAllNotifications();

        // Then
        assertThat(notifications).isNotNull().isEmpty();
    }

    @Test
    void post_shouldAllowMultipleNotificationsWithNullReferenceNumber() {
        // Given - create multiple notifications without explicitly setting referenceNumber
        NotificationDto notificationDto1 = createNotificationDto("GB", "Live cattle");
        NotificationDto notificationDto2 = createNotificationDto("IE", "Live sheep");
        NotificationDto notificationDto3 = createNotificationDto("FR", "Live pigs");

        // When - save all notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto1)
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto2)
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto3)
            .exchange()
            .expectStatus().isOk();

        // Then - verify all notifications were created with generated referenceNumbers
        List<Notification> allNotifications = findAllNotifications();
        assertThat(allNotifications).hasSize(3);
        assertThat(allNotifications)
            .extracting(Notification::getReferenceNumber)
            .allMatch(ref -> ref != null && ref.startsWith("DRAFT.IMP."));
        assertThat(allNotifications)
            .extracting(n -> n.getOrigin().getCountryCode())
            .containsExactlyInAnyOrder("GB", "IE", "FR");
    }

    @Test
    void post_shouldUpdateAllFieldsOnExistingNotification() {
        // Given — create a notification with initial values for all fields
        Species initialSpecies = new Species("OVI", "Ovine", 5, 2, "UK09876543210", "UK0987654300888");
        CommodityComplement initialComplement = new CommodityComplement("LIVE", 5, 2, List.of(initialSpecies));
        NotificationDto initial = NotificationDto.builder()
            .origin(new Origin("IE", "false", "REF-initial"))
            .commodity(Commodity.builder()
                .name("Live ovine animals")
                .commodityComplement(List.of(initialComplement))
                .build())
            .reasonForImport("TRANSIT")
            .additionalDetails(new AdditionalDetails("OTHER", "false"))
            .cphNumber("11/111/1111")
            .transport(Transport.builder().portOfEntry("GBBEL").arrivalDate(LocalDate.of(2026, 1, 1)).build())
            .build();

        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(initial)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — update every field with new values
        Species updatedSpecies = NotificationTestData.species();
        CommodityComplement updatedComplement = new CommodityComplement("LIVE", 10, 5, List.of(updatedSpecies));
        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(new Origin("GB", "true", "REF-updated"))
            .commodity(Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(updatedComplement))
                .build())
            .reasonForImport("PERMANENT")
            .additionalDetails(new AdditionalDetails("HUMAN_CONSUMPTION", "true"))
            .cphNumber("22/123/4567")
            .transport(Transport.builder().portOfEntry("GBFXT").arrivalDate(LocalDate.of(2026, 4, 22)).build())
            .build();

        Notification updated = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(updateDto)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody();

        // Then — verify response reflects updated values
        assertThat(updated).isNotNull();
        assertThat(updated.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(updated.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(updated.getOrigin().getRequiresRegionCode()).isEqualTo("true");
        assertThat(updated.getOrigin().getInternalReference()).isEqualTo("REF-updated");
        assertThat(updated.getCommodity().getName()).isEqualTo("Live bovine animals");
        CommodityComplement updatedComplementResult = updated.getCommodity().getCommodityComplement().getFirst();
        assertThat(updatedComplementResult.getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(updatedComplementResult.getTotalNoOfAnimals()).isEqualTo(10);
        assertThat(updatedComplementResult.getTotalNoOfPackages()).isEqualTo(5);
        Species updatedSpeciesResult = updatedComplementResult.getSpecies().getFirst();
        assertThat(updatedSpeciesResult.getValue()).isEqualTo("BOV");
        assertThat(updatedSpeciesResult.getText()).isEqualTo("Bovine");
        assertThat(updatedSpeciesResult.getNoOfAnimals()).isEqualTo(10);
        assertThat(updatedSpeciesResult.getNoOfPackages()).isEqualTo(5);
        assertThat(updatedSpeciesResult.getEarTag()).isEqualTo("UK01234567890");
        assertThat(updatedSpeciesResult.getPassport()).isEqualTo("UK0123456700999");
        assertThat(updated.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(updated.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(updated.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
        assertThat(updated.getCphNumber()).isEqualTo("22/123/4567");
        assertThat(updated.getTransport().getPortOfEntry()).isEqualTo("GBFXT");
        assertThat(updated.getTransport().getArrivalDate()).isEqualTo(LocalDate.of(2026, 4, 22));

        // Verify only one notification exists and reload via API
        List<Notification> all = findAllNotifications();
        assertThat(all).hasSize(1);
        Notification persisted = all.getFirst();
        assertThat(persisted.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(persisted.getOrigin().getRequiresRegionCode()).isEqualTo("true");
        assertThat(persisted.getOrigin().getInternalReference()).isEqualTo("REF-updated");
        assertThat(persisted.getCommodity().getName()).isEqualTo("Live bovine animals");
        assertThat(persisted.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(persisted.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(persisted.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
        assertThat(persisted.getCphNumber()).isEqualTo("22/123/4567");
        assertThat(persisted.getTransport().getPortOfEntry()).isEqualTo("GBFXT");
        assertThat(persisted.getTransport().getArrivalDate()).isEqualTo(LocalDate.of(2026, 4, 22));
    }

    @Test
    void delete_shouldDeleteNotifications_whenAllReferenceNumbersExist() {
        // Given — create two notifications
        String ref1 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String ref2 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("IE", "Live sheep"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — delete both by reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-001")
            .header("User-Id", "user-001")
            .bodyValue(List.of(ref1, ref2))
            .exchange()
            .expectStatus().isNoContent();

        // Then — both are gone
        assertThat(findAllNotifications()).isEmpty();
    }

    @Test
    void delete_shouldCreateSuccessAuditRecord_whenNotificationsDeleted() {
        // Given
        String ref = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-audit-success")
            .header("User-Id", "user-audit")
            .bodyValue(List.of(ref))
            .exchange()
            .expectStatus().isNoContent();

        // Then — a SUCCESS audit record is persisted
        List<Audit> audits = auditRepository.findAll();
        assertThat(audits).hasSize(1);
        Audit audit = audits.getFirst();
        assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(audit.getNotificationReferenceNumbers()).containsExactly(ref);
        assertThat(audit.getNumberOfNotifications()).isEqualTo(1);
        assertThat(audit.getTraceId()).isEqualTo("trace-audit-success");
        assertThat(audit.getUserId()).isEqualTo("user-audit");
        assertThat(audit.getTimestamp()).isNotNull();
    }

    @Test
    void delete_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // When — attempt to delete a non-existent reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-002")
            .header("User-Id", "user-002")
            .bodyValue(List.of("DRAFT.IMP.2026.DOESNOTEXIST"))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString("DRAFT.IMP.2026.DOESNOTEXIST"));
    }

    @Test
    void delete_shouldCreateFailureAuditRecord_whenReferenceNumberDoesNotExist() {
        // When — attempt to delete a non-existent reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-audit-failure")
            .header("User-Id", "user-audit-failure")
            .bodyValue(List.of("DRAFT.IMP.2026.DOESNOTEXIST"))
            .exchange()
            .expectStatus().isNotFound();

        // Then — a FAILURE audit record is persisted
        List<Audit> audits = auditRepository.findAll();
        assertThat(audits).hasSize(1);
        Audit audit = audits.getFirst();
        assertThat(audit.getResult()).isEqualTo(Result.FAILURE);
        assertThat(audit.getNotificationReferenceNumbers()).containsExactly("DRAFT.IMP.2026.DOESNOTEXIST");
        assertThat(audit.getTraceId()).isEqualTo("trace-audit-failure");
        assertThat(audit.getUserId()).isEqualTo("user-audit-failure");
    }

    @Test
    void delete_shouldReturn404AndNotDeleteAnything_whenOneReferenceNumberIsMissing() {
        // Given — create one notification
        String existingRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("FR", "Live pigs"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — attempt to delete the existing one plus a missing one
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-003")
            .header("User-Id", "user-003")
            .bodyValue(List.of(existingRef, "DRAFT.IMP.2026.MISSING"))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("DRAFT.IMP.2026.MISSING"));

        // Then — the existing notification was NOT deleted (all-or-nothing)
        List<Notification> remaining = findAllNotifications();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getReferenceNumber()).isEqualTo(existingRef);
    }

    @Test
    void delete_shouldReturn400_whenListIsEmpty() {
        // When
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .bodyValue(List.of())
            .exchange()
            .expectStatus().isBadRequest();

        // Then — DB state should be unchanged (empty as per @BeforeEach)
        assertThat(findAllNotifications()).isEmpty();
    }

    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsMissing() {
        // When — no Trade-Imports-Animals-Admin-Secret header
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .bodyValue(List.of("DRAFT.IMP.2026.ANY"))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsIncorrect() {
        // When — wrong secret value
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, "wrong-secret")
            .bodyValue(List.of("DRAFT.IMP.2026.ANY"))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void findAllReferenceNumbers_shouldReturnEmptyList_whenNoNotificationsExist() {
        // When
        List<String> referenceNumbers = findAllReferenceNumbers();

        // Then
        assertThat(referenceNumbers).isNotNull().isEmpty();
    }

    @Test
    void findAllReferenceNumbers_shouldReturnOnlyReferenceNumbers() {
        // Given — create two notifications
        String ref1 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String ref2 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("IE", "Live sheep"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When
        List<String> referenceNumbers = findAllReferenceNumbers();

        // Then — only strings returned, no full document fields
        assertThat(referenceNumbers).hasSize(2);
        assertThat(referenceNumbers).containsExactlyInAnyOrder(ref1, ref2);
    }

    @Test
    void findByRef_shouldReturnHydratedNotification_withAccompanyingDocuments() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live bovine animals"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // And directly persist an accompanying document (bypasses cdp-uploader)
        AccompanyingDocument document = AccompanyingDocument.builder()
            .notificationReferenceNumber(referenceNumber)
            .uploadId("upload-it-test-001")
            .documentType(DocumentType.ITAHC)
            .documentReference("UK/GB/2026/IT-001")
            .scanStatus(ScanStatus.COMPLETE)
            .build();
        accompanyingDocumentRepository.save(document);

        // When
        NotificationResponse response = webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationResponse.class)
            .returnResult().getResponseBody();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.referenceNumber()).isEqualTo(referenceNumber);
        assertThat(response.origin().getCountryCode()).isEqualTo("GB");
        assertThat(response.accompanyingDocuments()).hasSize(1);
        assertThat(response.accompanyingDocuments().getFirst().uploadId()).isEqualTo("upload-it-test-001");
        assertThat(response.accompanyingDocuments().getFirst().documentType()).isEqualTo(DocumentType.ITAHC);
        assertThat(response.accompanyingDocuments().getFirst().scanStatus()).isEqualTo(ScanStatus.COMPLETE);
    }

    @Test
    void findByRef_shouldReturn200WithEmptyDocuments_whenNoDocumentsUploaded() {
        // Given — notification with no documents
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("IE", "Live sheep"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When
        NotificationResponse response = webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationResponse.class)
            .returnResult().getResponseBody();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.referenceNumber()).isEqualTo(referenceNumber);
        assertThat(response.accompanyingDocuments()).isEmpty();
    }

    @Test
    void findByRef_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // Given
        // No notification exists for DRAFT.IMP.2026.DOESNOTEXIST

        // When
        // Then
        webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}", "DRAFT.IMP.2026.DOESNOTEXIST")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString("DRAFT.IMP.2026.DOESNOTEXIST"));
    }

    @Test
    void delete_shouldCascadeDeleteAccompanyingDocuments_whenNotificationDeleted() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("FR", "Live pigs"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // And persist an accompanying document directly
        AccompanyingDocument document = AccompanyingDocument.builder()
            .notificationReferenceNumber(referenceNumber)
            .uploadId("upload-cascade-test-001")
            .documentType(DocumentType.VETERINARY_HEALTH_CERTIFICATE)
            .scanStatus(ScanStatus.COMPLETE)
            .build();
        accompanyingDocumentRepository.save(document);
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(referenceNumber))
            .hasSize(1);

        // When — delete the notification
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-cascade-001")
            .header("User-Id", "user-cascade-001")
            .bodyValue(List.of(referenceNumber))
            .exchange()
            .expectStatus().isNoContent();

        // Then — notification and its documents are both gone
        assertThat(notificationRepository.findByReferenceNumber(referenceNumber)).isEmpty();
        assertThat(accompanyingDocumentRepository.findAllByNotificationReferenceNumber(referenceNumber))
            .isEmpty();
    }

    private List<String> findAllReferenceNumbers() {
        return webClient("NoAuth")
            .get()
            .uri(NOTIFICATION_ENDPOINT + "/reference-numbers")
            .exchange()
            .expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<List<String>>() {})
            .returnResult().getResponseBody();
    }

    private List<Notification> findAllNotifications() {
        return webClient("NoAuth")
            .get()
            .uri(NOTIFICATION_ENDPOINT)
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(Notification.class)
            .returnResult().getResponseBody();
    }

    private NotificationDto createNotificationDto(String countryCode, String commodity) {
        Origin origin = new Origin();
        origin.setCountryCode(countryCode);

        return NotificationDto.builder()
            .origin(origin)
            .commodity(Commodity.builder().name(commodity).build())
            .build();
    }
}
