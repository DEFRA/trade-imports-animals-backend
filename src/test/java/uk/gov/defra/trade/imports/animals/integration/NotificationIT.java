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
import uk.gov.defra.trade.imports.animals.notification.NotificationPageResponse;
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
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.NotificationResponse;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.notification.Species;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;
import uk.gov.defra.trade.imports.animals.utils.NotificationTestData;

class NotificationIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String ADMIN_SECRET_HEADER = "Trade-Imports-Animals-Admin-Secret";
    private static final String VALID_ADMIN_SECRET = "test-admin-secret";
    private static final String HEADER_TRACE_ID = NotificationController.HEADER_TRACE_ID;
    private static final String REF_FORMAT_REGEX = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;
    private static final String NONEXISTENT_REF = "GBN-AG-00-000000";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
        accompanyingDocumentRepository.deleteAll();
        outboxEventRepository.deleteAll();
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
        assertThat(created.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertNotificationMappedFields(created);

        // Verify persisted — reload via API
        Notification persisted = notificationRepository.findByReferenceNumber(created.getReferenceNumber())
            .orElseThrow();
        assertThat(persisted.getId()).isEqualTo(created.getId());
        assertNotificationMappedFields(persisted);
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

        // When — page-size is 2 in integration-test profile, so page 0 has 2 items
        NotificationPageResponse page0 = findAllNotificationsPage(0);
        NotificationPageResponse page1 = findAllNotificationsPage(1);

        // Then
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.content()).hasSize(2);
        assertThat(page1.content()).hasSize(1);
    }

    @Test
    void findAll_shouldReturnEmptyPage_whenNoNotifications() {
        // When
        NotificationPageResponse page = findAllNotificationsPage();

        // Then
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.page()).isZero();
    }

    @Test
    void findAll_shouldReturnNotificationsOrderedByArrivalDateDescending() {
        // Given
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("GB", LocalDate.of(2026, 1, 10)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, 6, 15)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, 3, 1)))
            .exchange()
            .expectStatus().isOk();

        // When — page-size is 2 in integration-test profile; all 3 fit across 2 pages
        NotificationPageResponse page0 = findAllNotificationsPage(0);
        NotificationPageResponse page1 = findAllNotificationsPage(1);

        // Then — arrival dates across pages are ordered descending
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 3, 1));
        assertThat(page1.content()).hasSize(1);
        assertThat(page1.content().getFirst().getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void findAll_notifications_without_arrivalDate_list_afterThoseWithDates() {
        // Given — dated notifications and drafts without transport.arrivalDate
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, 6, 15)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, 1, 10)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithTransportButNoArrivalDate("DE"))
            .exchange()
            .expectStatus().isOk();

        // When — page-size=2, so page 0 has dated items, page 1 has the null-date items
        NotificationPageResponse page0 = findAllNotificationsPage(0);
        NotificationPageResponse page1 = findAllNotificationsPage(1);

        // Then — dated first (desc), then those with no arrival date
        assertThat(page0.totalElements()).isEqualTo(4);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 1, 10));
        assertThat(page1.content()).hasSize(2);
        assertThat(page1.content())
            .extracting(this::extractArrivalDate)
            .containsOnlyNulls();
    }

    @Test
    void findAll_notifications_with_draft_and_submitted_statuses() {
        // Given — mix of DRAFT (with and without arrival date) and SUBMITTED
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange()
            .expectStatus().isOk();

        String submittedRef = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, 6, 15)))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, 3, 1)))
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", submittedRef)
            .exchange()
            .expectStatus().isOk();

        // When — page-size=2 in test profile
        NotificationPageResponse page0 = findAllNotificationsPage(0);
        NotificationPageResponse page1 = findAllNotificationsPage(1);

        // Then — no status filter; drafts and submitted both appear, ordered by arrival date desc
        assertThat(page0.totalElements()).isEqualTo(3);
        List<NotificationDto> all = new java.util.ArrayList<>(page0.content());
        all.addAll(page1.content());

        assertThat(all).hasSize(3);
        assertThat(all)
            .extracting(NotificationDto::getStatus)
            .containsExactlyInAnyOrder(
                NotificationStatus.DRAFT,
                NotificationStatus.DRAFT,
                NotificationStatus.SUBMITTED);
        assertThat(all.getFirst().getReferenceNumber()).isEqualTo(submittedRef);
        assertThat(all.getFirst().getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(all.getFirst().getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(all.get(1).getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(extractArrivalDate(all.get(2))).isNull();
    }

    @Test
    void findAll_shouldExcludeDeletedNotifications() {
        // Given — create three notifications, submit one, soft-delete one
        String draftRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String submittedRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("IE", "Live sheep"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        String deletedRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("FR", "Live pigs"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", submittedRef)
            .exchange().expectStatus().isOk();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", deletedRef)
            .exchange().expectStatus().isOk();

        // When
        List<NotificationDto> notifications = findAllNotificationsPage(0).content();

        // Then — only DRAFT and SUBMITTED are returned; DELETED is excluded
        assertThat(notifications).hasSize(2);
        assertThat(notifications)
            .extracting(NotificationDto::getReferenceNumber)
            .containsExactlyInAnyOrder(draftRef, submittedRef);
        assertThat(notifications)
            .extracting(NotificationDto::getStatus)
            .doesNotContain(NotificationStatus.DELETED);
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
        NotificationPageResponse pageResponse = findAllNotificationsPage(0);
        NotificationPageResponse page1Response = findAllNotificationsPage(1);
        List<NotificationDto> allNotifications = new java.util.ArrayList<>(pageResponse.content());
        allNotifications.addAll(page1Response.content());
        assertThat(allNotifications).hasSize(3);
        assertThat(allNotifications)
            .extracting(NotificationDto::getReferenceNumber)
            .allMatch(ref -> ref != null && ref.startsWith("GBN-AG-"));
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
        assertNotificationMappedFields(updated, "REF-updated");

        // Verify only one notification exists and reload via API
        List<NotificationDto> all = findAllNotifications();
        assertThat(all).hasSize(1);
        Notification persisted = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertNotificationMappedFields(persisted, "REF-updated");
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
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void delete_shouldCreateFailureAuditRecord_whenReferenceNumberDoesNotExist() {
        // When — attempt to delete a non-existent reference number
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .header("x-cdp-request-id", "trace-audit-failure")
            .header("User-Id", "user-audit-failure")
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound();

        // Then — a FAILURE audit record is persisted
        List<Audit> audits = auditRepository.findAll();
        assertThat(audits).hasSize(1);
        Audit audit = audits.getFirst();
        assertThat(audit.getResult()).isEqualTo(Result.FAILURE);
        assertThat(audit.getNotificationReferenceNumbers()).containsExactly(NONEXISTENT_REF);
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
            .bodyValue(List.of(existingRef, NONEXISTENT_REF))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));

        // Then — the existing notification was NOT deleted (all-or-nothing)
        List<NotificationDto> remaining = findAllNotifications();
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
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsIncorrect() {
        // When — wrong secret value
        webClient("NoAuth")
            .method(HttpMethod.DELETE).uri(NOTIFICATION_ENDPOINT)
            .header(ADMIN_SECRET_HEADER, "wrong-secret")
            .bodyValue(List.of(NONEXISTENT_REF))
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void submit_shouldTransitionStatusFromDraftToSubmitted() {
        // Given — create a notification (starts as DRAFT)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — submit the notification
        Notification submitted = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — status is SUBMITTED
        assertThat(submitted).isNotNull();
        assertThat(submitted.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(submitted.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(submitted.getUpdated()).isNotNull();
    }

    @Test
    void submit_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void submit_shouldWriteOutboxEventWithCorrectEnvelope() {
        // Given — create a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — submit it with a trace ID
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-outbox-001")
            .exchange()
            .expectStatus().isOk();

        // Then — one outbox event exists with the correct envelope
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();

        assertThat(event.getAggregateId())
            .isEqualTo(OutboxService.buildAggregateId(referenceNumber));
        assertThat(event.getAggregateType()).isEqualTo("Notification");
        assertThat(event.getSubType()).isEqualTo("GBN-AG");
        assertThat(event.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(event.getAggregateVersion()).isEqualTo(1L);
        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getMetadata().getCorrelationId()).isEqualTo("trace-outbox-001");
        assertThat(event.getMetadata().getSchemaVersion()).isEqualTo("1");
        assertThat(event.getData().get("referenceNumber")).isEqualTo(referenceNumber);
    }

    @Test
    void submit_shouldIncrementAggregateVersion_onSubsequentSubmissions() {
        // Given — create and submit a notification (version 1)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Reset status to DRAFT so we can submit again (simulates re-submission scenario)
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notification.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notification);

        // When — submit again (version 2)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — two outbox events with incrementing versions
        List<OutboxEvent> events = outboxEventRepository.findAll()
            .stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);
    }

    @Test
    void submit_shouldNotWriteOutboxEvent_whenNotificationDoesNotExist() {
        // When
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound();

        // Then — no outbox events written
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void post_shouldNotWriteOutboxEvent_whenCreatingDraftNotification() {
        // When — create a draft notification
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk();

        // Then — no outbox events written
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void softDelete_shouldTransitionStatusToDeleted_whenNotificationIsDraft() {
        // Given — create a notification (starts as DRAFT)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — soft-delete the notification
        Notification result = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — status transitions to DELETED
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void softDelete_shouldTransitionStatusToDeleted_whenNotificationIsSubmitted() {
        // Given — create and submit a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // When — soft-delete the submitted notification
        Notification result = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — status transitions to DELETED
        assertThat(result).isNotNull();
        assertThat(result.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void softDelete_shouldReturn404_whenReferenceNumberDoesNotExist() {
        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void softDelete_shouldReturn400_whenNotificationIsAlreadyDeleted() {
        // Given — create and soft-delete a notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange().expectStatus().isOk();

        // When — attempt to soft-delete again
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", referenceNumber)
            .exchange()
            .expectStatus().isBadRequest();
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
        // When / Then — no notification exists for NONEXISTENT_REF
        webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(
                Matchers.containsString(NONEXISTENT_REF));
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

    private NotificationPageResponse findAllNotificationsPage() {
        return findAllNotificationsPage(0);
    }

    private NotificationPageResponse findAllNotificationsPage(int page) {
        return webClient("NoAuth")
            .get()
            .uri(NOTIFICATION_ENDPOINT + "?page=" + page)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationPageResponse.class)
            .returnResult().getResponseBody();
    }

    private List<NotificationDto> findAllNotifications() {
        return findAllNotificationsPage().content();
    }

    private void assertNotificationMappedFields(Notification notification) {
        assertNotificationMappedFields(notification, "REF-001");
    }

    private void assertNotificationMappedFields(Notification notification, String internalReference) {
        assertThat(notification.getOrigin())
            .extracting(Origin::getCountryCode, Origin::getRequiresRegionCode, Origin::getInternalReference)
            .containsExactly("GB", "true", internalReference);

        assertThat(notification.getCommodity())
            .extracting(Commodity::getName)
            .isEqualTo("Live bovine animals");

        CommodityComplement complement = notification.getCommodity().getCommodityComplement().getFirst();
        assertThat(complement)
            .extracting(
                CommodityComplement::getTypeOfCommodity,
                CommodityComplement::getTotalNoOfAnimals,
                CommodityComplement::getTotalNoOfPackages)
            .containsExactly("LIVE", 10, 5);

        Species species = complement.getSpecies().getFirst();
        assertThat(species)
            .extracting(
                Species::getValue,
                Species::getText,
                Species::getNoOfAnimals,
                Species::getNoOfPackages,
                Species::getEarTag,
                Species::getPassport)
            .containsExactly("BOV", "Bovine", 10, 5, "UK01234567890", "UK0123456700999");

        assertThat(notification)
            .extracting(Notification::getReasonForImport, Notification::getCphNumber)
            .containsExactly("PERMANENT", "22/123/4567");

        assertThat(notification.getAdditionalDetails())
            .extracting(AdditionalDetails::getCertifiedFor, AdditionalDetails::getUnweanedAnimals)
            .containsExactly("HUMAN_CONSUMPTION", "true");

        assertThat(notification.getTransport())
            .extracting(Transport::getPortOfEntry, Transport::getArrivalDate)
            .containsExactly("GBFXT", LocalDate.of(2026, 4, 22));
    }

    @Test
    void getOutboxEvents_shouldReturnEventsInChronologicalOrder() {
        // Given — create and submit a notification (version 1)
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // Reset status to DRAFT so we can submit again
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        notification.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notification);

        // Submit again (version 2)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        // When
        List<OutboxEvent> events = webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}/outbox-events", referenceNumber)
            .exchange().expectStatus().isOk()
            .expectBodyList(OutboxEvent.class).returnResult()
            .getResponseBody();

        // Then — two events returned in version ascending order
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(events.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(events.get(0).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
    }

    @Test
    void getOutboxEvents_shouldReturnEmptyList_whenNoEventsExist() {
        // Given — create a notification but do not submit it
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When
        List<OutboxEvent> events = webClient("NoAuth")
            .get().uri(NOTIFICATION_ENDPOINT + "/{ref}/outbox-events", referenceNumber)
            .exchange().expectStatus().isOk()
            .expectBodyList(OutboxEvent.class).returnResult()
            .getResponseBody();

        // Then
        assertThat(events).isEmpty();
    }

    private NotificationDto createNotificationDto(String countryCode, String commodity) {
        Origin origin = new Origin();
        origin.setCountryCode(countryCode);

        return NotificationDto.builder()
            .origin(origin)
            .commodity(Commodity.builder().name(commodity).build())
            .build();
    }

    private NotificationDto notificationDtoWithArrivalDate(String countryCode, LocalDate arrivalDate) {
        return NotificationDto.builder()
            .origin(new Origin(countryCode, null, null))
            .commodity(Commodity.builder().name("Live animals").build())
            .transport(Transport.builder().arrivalDate(arrivalDate).build())
            .build();
    }

    private NotificationDto notificationDtoWithTransportButNoArrivalDate(String countryCode) {
        return NotificationDto.builder()
            .origin(new Origin(countryCode, null, null))
            .commodity(Commodity.builder().name("Live animals").build())
            .transport(Transport.builder().portOfEntry("GBFXT").build())
            .build();
    }

    private LocalDate extractArrivalDate(NotificationDto notification) {
        if (notification.getTransport() == null) {
            return null;
        }
        return notification.getTransport().getArrivalDate();
    }
}
