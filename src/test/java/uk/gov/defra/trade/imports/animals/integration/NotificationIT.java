package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import uk.gov.defra.trade.imports.animals.audit.Audit;
import uk.gov.defra.trade.imports.animals.audit.AuditRepository;
import uk.gov.defra.trade.imports.animals.audit.Result;
import uk.gov.defra.trade.imports.animals.notification.AdditionalDetails;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.CommodityComplement;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.Species;

class NotificationIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String ADMIN_SECRET_HEADER = "Trade-Imports-Animals-Admin-Secret";
    private static final String VALID_ADMIN_SECRET = "test-admin-secret";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
    }

    @Test
    void post_shouldCreateNewNotification() {
        // Given
        NotificationDto notificationDto = createNotificationDto("GB", "Live bovine animals");

        // When
        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Then
        Notification created = result.getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).isNotNull();
        assertThat(created.getReferenceNumber()).startsWith("DRAFT.IMP.");
        assertThat(created.getOrigin()).isNotNull();
        assertThat(created.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(created.getCommodity().getName()).isEqualTo("Live bovine animals");
        assertThat(created.getReferenceNumber()).matches("DRAFT\\.IMP\\.\\d{4}\\..+");
        assertThat(created.getReferenceNumber()).contains(created.getId());
    }

    @Test
    void post_shouldCreateNotificationWithOriginDetails() {
        // Given
        Origin origin = new Origin("IE", "true", "REF-001");
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(origin)
            .commodity(Commodity.builder().name("Live cattle").build())
            .build();

        // When
        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Then
        Notification created = result.getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).isNotNull();
        assertThat(created.getOrigin().getCountryCode()).isEqualTo("IE");
        assertThat(created.getOrigin().getRequiresRegionCode()).isEqualTo("true");
        assertThat(created.getOrigin().getInternalReference()).isEqualTo("REF-001");
        assertThat(created.getCommodity().getName()).isEqualTo("Live cattle");
    }

    @Test
    void post_shouldUpdateExistingNotification() {
        // Given - create initial notification
        NotificationDto initial = createNotificationDto("FR", "Live sheep");
        EntityExchangeResult<Notification> createResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(initial)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        String createdId = createResult.getResponseBody().getId();
        String referenceNumber = createResult.getResponseBody().getReferenceNumber();

        // When - update the notification
        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(new Origin("ES", "false", "REF-002"))
            .commodity(Commodity.builder().name("Live pigs").build())
            .build();

        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(updateDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Then
        Notification updated = result.getResponseBody();
        assertThat(updated).isNotNull();
        assertThat(updated.getId()).isEqualTo(createdId);
        assertThat(updated.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(updated.getOrigin().getCountryCode()).isEqualTo("ES");
        assertThat(updated.getCommodity().getName()).isEqualTo("Live pigs");

        // Verify only one notification exists
        assertThat(findAllNotifications()).hasSize(1);
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
    void post_shouldUpdateExistingNotificationWithSameReferenceNumber() {
        // Given - create first notification
        NotificationDto notificationDto1 = createNotificationDto("GB", "Live cattle");
        EntityExchangeResult<Notification> createResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto1)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        String referenceNumber = createResult.getResponseBody().getReferenceNumber();

        // When - attempt to create second notification with same referenceNumber
        Origin origin = new Origin();
        origin.setCountryCode("IE");
        NotificationDto notificationDto2 = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(origin)
            .commodity(Commodity.builder().name("Live sheep").build())
            .build();

        // Then - expect updated notification
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto2)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Verify only one notification was saved
        List<Notification> allNotifications = findAllNotifications();
        assertThat(allNotifications).hasSize(1);
        assertThat(allNotifications.getFirst().getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(allNotifications.getFirst().getOrigin().getCountryCode()).isEqualTo("IE");
        assertThat(allNotifications.getFirst().getCommodity().getName()).isEqualTo("Live sheep");
    }

    @Test
    void fullCrudFlow_shouldWorkEndToEnd() {
        // 1. Create notification
        NotificationDto createDto = createNotificationDto("NL", "Live horses");
        EntityExchangeResult<Notification> createResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        Notification created = createResult.getResponseBody();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).isNotNull();
        assertThat(created.getReferenceNumber()).startsWith("DRAFT.IMP.");
        assertThat(created.getOrigin().getCountryCode()).isEqualTo("NL");
        assertThat(created.getCommodity().getName()).isEqualTo("Live horses");

        // 2. Verify findAll returns the created notification
        List<Notification> allNotifications = findAllNotifications();
        assertThat(allNotifications).hasSize(1);
        assertThat(allNotifications.get(0).getId()).isEqualTo(created.getId());

        // 3. Update the notification
        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(created.getReferenceNumber())
            .origin(new Origin("BE", "false", "REF-BE-001"))
            .commodity(Commodity.builder().name("Live donkeys").build())
            .build();

        EntityExchangeResult<Notification> updateResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(updateDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        Notification updated = updateResult.getResponseBody();
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getReferenceNumber()).isEqualTo(created.getReferenceNumber());
        assertThat(updated.getOrigin().getCountryCode()).isEqualTo("BE");
        assertThat(updated.getCommodity().getName()).isEqualTo("Live donkeys");

        // 4. Verify only one notification exists
        assertThat(findAllNotifications()).hasSize(1);

        // 5. Verify the notification was updated in database
        Notification persisted = notificationRepository.findById(created.getId()).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getOrigin().getCountryCode()).isEqualTo("BE");
        assertThat(persisted.getCommodity().getName()).isEqualTo("Live donkeys");
    }

    @Test
    void post_shouldHandleDifferentCommodityTypes() {
        // Given - create notifications with different commodity types
        NotificationDto cattle = createNotificationDto("GB", "Live bovine animals");
        NotificationDto sheep = createNotificationDto("IE", "Live ovine animals");
        NotificationDto pigs = createNotificationDto("FR", "Live porcine animals");

        // When - create all notifications
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(cattle).exchange()
            .expectStatus().isOk();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(sheep).exchange()
            .expectStatus().isOk();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(pigs).exchange()
            .expectStatus().isOk();

        // Then
        List<Notification> notifications = findAllNotifications();
        assertThat(notifications).hasSize(3);
        assertThat(notifications)
            .extracting(Notification::getCommodity).extracting(Commodity::getName)
            .containsExactlyInAnyOrder(
                "Live bovine animals",
                "Live ovine animals",
                "Live porcine animals"
            );
    }

    @Test
    void post_shouldCreateNotificationWithAdditionalDetails() {
        // Given
        AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "true");
        Species species = new Species("BOV", "Bovine", 10, null);
        CommodityComplement complement = new CommodityComplement("LIVE", 10, null, List.of(species));
        Commodity commodity = Commodity.builder()
            .name("Live bovine animals")
            .commodityComplement(List.of(complement))
            .build();
        NotificationDto notificationDto = NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(commodity)
            .additionalDetails(additionalDetails)
            .reasonForImport("PERMANENT")
            .build();

        // When
        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Then
        Notification created = result.getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.getAdditionalDetails()).isNotNull();
        assertThat(created.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(created.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("true");
        assertThat(created.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(created.getCommodity().getCommodityComplement()).hasSize(1);
        assertThat(created.getCommodity().getCommodityComplement().getFirst().getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(created.getCommodity().getCommodityComplement().getFirst().getTotalNoOfAnimals()).isEqualTo(10);
        assertThat(created.getCommodity().getCommodityComplement().getFirst().getSpecies()).hasSize(1);
        assertThat(created.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst().getValue()).isEqualTo("BOV");
        assertThat(created.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst().getText()).isEqualTo("Bovine");
        assertThat(created.getCommodity().getCommodityComplement().getFirst().getSpecies().getFirst().getNoOfAnimals()).isEqualTo(10);
    }

    @Test
    void post_shouldUpdateAdditionalDetails_onExistingNotification() {
        // Given — create a notification without additionalDetails
        NotificationDto initial = createNotificationDto("GB", "Live bovine animals");
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(initial)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — update with additionalDetails
        AdditionalDetails additionalDetails = new AdditionalDetails("HUMAN_CONSUMPTION", "false");
        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(Commodity.builder().name("Live bovine animals").build())
            .additionalDetails(additionalDetails)
            .build();

        Notification updated = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(updateDto)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody();

        // Then
        assertThat(updated).isNotNull();
        assertThat(updated.getAdditionalDetails()).isNotNull();
        assertThat(updated.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
        assertThat(updated.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("false");

        // Verify persisted
        Notification persisted = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(persisted.getAdditionalDetails().getCertifiedFor()).isEqualTo("HUMAN_CONSUMPTION");
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
