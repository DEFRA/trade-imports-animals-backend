package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
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
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationContentSnapshot;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationPageResponse;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationResponse;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberPageResponse;
import uk.gov.defra.trade.imports.animals.notification.Species;
import uk.gov.defra.trade.imports.animals.notification.Transport;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
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
            .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
            .meansOfTransport(MeansOfTransport.RAILWAY)
            .transportIdentification("Train 4471, wagon 12")
            .transportDocumentReference("CIM-CONSIGNMENT-001")
            .transitedCountries(List.of("FR", "DE"))
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
        NotificationPageResponse page0 = findAllNotificationsPage(1);
        NotificationPageResponse page1 = findAllNotificationsPage(2);

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
        assertThat(page.page()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "further-keeping",
        "slaughter",
        "confined-establishment",
        "germinal-products",
        "registered-equine-animal",
        "travelling-circus-animal-act",
        "exhibition",
        "event-or-activity-near-borders",
        "release-into-the-wild",
        "dispatch-centre",
        "relaying-area-purification-centre",
        "ornamental-aquaculture-establishment",
        "technical-use",
        "quarantine-or-similar-establishment",
        "live-aquatic-animals-for-human-consumption",
        "other"
    })
    void certifiedFor_shouldRoundTripTheFrontendVocabularyThroughPutAndGet(
        String certifiedFor) {
        String referenceNumber = "GBN-AG-26-CF0001";
        NotificationDto notification = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .additionalDetails(new AdditionalDetails(certifiedFor, null))
            .build();

        webClient("NoAuth")
            .put()
            .uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}", referenceNumber)
            .bodyValue(notification)
            .exchange()
            .expectStatus().isCreated();

        Notification persisted = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(persisted.getAdditionalDetails().getCertifiedFor()).isEqualTo(certifiedFor);

        NotificationResponse response = webClient("NoAuth")
            .get()
            .uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.additionalDetails().getCertifiedFor()).isEqualTo(certifiedFor);
    }

    @Test
    void findAll_shouldReturnNotificationsOrderedByArrivalDateDescending() {
        // Given
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("GB", LocalDate.of(2026, Month.JANUARY, 10)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.MARCH, 1)))
            .exchange()
            .expectStatus().isOk();

        // When — page-size is 2 in integration-test profile; all 3 fit across 2 pages
        NotificationPageResponse page0 = findAllNotificationsPage(1);
        NotificationPageResponse page1 = findAllNotificationsPage(2);

        // Then — arrival dates across pages are ordered descending
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(
                LocalDate.of(2026, Month.JUNE, 15),
                LocalDate.of(2026, Month.MARCH, 1));
        assertThat(page1.content()).hasSize(1);
        assertThat(page1.content().getFirst().getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, Month.JANUARY, 10));
    }

    @Test
    void findAll_shouldReturnNotificationsOrderedByCreatedAtAscending_whenSortRequested() {
        String refOlder = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("GB", LocalDate.of(2026, Month.JANUARY, 10)))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        String refNewer = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15)))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        Notification older = notificationRepository.findByReferenceNumber(refOlder).orElseThrow();
        older.setCreated(LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0));
        notificationRepository.save(older);

        Notification newer = notificationRepository.findByReferenceNumber(refNewer).orElseThrow();
        newer.setCreated(LocalDateTime.of(2026, Month.JANUARY, 2, 10, 0));
        notificationRepository.save(newer);

        NotificationPageResponse page = findAllNotificationsPage(1, "createdAt,asc");

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().getFirst().getReferenceNumber()).isEqualTo(refOlder);
        assertThat(page.content().get(1).getReferenceNumber()).isEqualTo(refNewer);
    }

    @Test
    void findAll_notifications_with_null_arrivalDate_list_beforeThoseWithValid_ArrivalDates_whenSortedAscending() {
        // Given — mix of dated and null/missing transport.arrivalDate notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.JANUARY, 10)))
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

        // When — page-size=2; ascending sort must place nulls first (NULLS FIRST)
        NotificationPageResponse page0 = findAllNotificationsPage(1, "arrivalDate,asc");
        NotificationPageResponse page1 = findAllNotificationsPage(2, "arrivalDate,asc");

        // Then — null arrival dates first, then dated items in ascending order
        assertThat(page0.totalElements()).isEqualTo(4);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(this::extractArrivalDate)
            .containsOnlyNulls();
        assertThat(page1.content()).hasSize(2);
        assertThat(page1.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(LocalDate.of(2026, Month.JANUARY, 10), LocalDate.of(2026, Month.JUNE, 15));
    }

    @Test
    void findAll_notifications_with_null_arrivalDate_list_afterThoseWithValid_ArrivalDates_whenSortedDescending() {
        // Given — mix of dated and null/missing transport.arrivalDate notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15)))
            .exchange()
            .expectStatus().isOk();
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.JANUARY, 10)))
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

        // When — page-size=2; descending sort must place nulls last (NULLS LAST)
        NotificationPageResponse page0 = findAllNotificationsPage(1, "arrivalDate,desc");
        NotificationPageResponse page1 = findAllNotificationsPage(2, "arrivalDate,desc");

        // Then — dated items first in descending order, then null arrival dates
        assertThat(page0.totalElements()).isEqualTo(4);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content())
            .extracting(n -> n.getTransport().getArrivalDate())
            .containsExactly(LocalDate.of(2026, Month.JUNE, 15), LocalDate.of(2026, Month.JANUARY, 10));
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
            .bodyValue(notificationDtoWithArrivalDate("IE", LocalDate.of(2026, Month.JUNE, 15)))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult()
            .getResponseBody()
            .getReferenceNumber();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notificationDtoWithArrivalDate("FR", LocalDate.of(2026, Month.MARCH, 1)))
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", submittedRef)
            .exchange()
            .expectStatus().isOk();

        // When — page-size=2 in test profile
        NotificationPageResponse page0 = findAllNotificationsPage(1);
        NotificationPageResponse page1 = findAllNotificationsPage(2);

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
            .isEqualTo(LocalDate.of(2026, Month.JUNE, 15));
        assertThat(all.get(1).getTransport().getArrivalDate())
            .isEqualTo(LocalDate.of(2026, Month.MARCH, 1));
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
        List<NotificationDto> notifications = findAllNotificationsPage(1).content();

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
        NotificationPageResponse pageResponse = findAllNotificationsPage(1);
        NotificationPageResponse page1Response = findAllNotificationsPage(2);
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
            .transport(Transport.builder().portOfEntry("GBBEL").arrivalDate(LocalDate.of(2026, Month.JANUARY, 1)).build())
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
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .meansOfTransport(MeansOfTransport.RAILWAY)
                .transportIdentification("Train 4471, wagon 12")
                .transportDocumentReference("CIM-CONSIGNMENT-001")
                .transitedCountries(List.of("FR", "DE"))
                .build())
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
    void post_shouldClearTransitedCountries_whenUpdatedToMeansThatDoesNotRequireTransit() {
        NotificationDto initial = NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(Commodity.builder().name("Live bovine animals").build())
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .meansOfTransport(MeansOfTransport.ROAD_VEHICLE)
                .transportIdentification("HG12 ABC")
                .transportDocumentReference("CMR-001")
                .transitedCountries(List.of("FR", "DE"))
                .build())
            .build();

        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(initial)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        NotificationDto updateDto = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(new Origin("GB", "true", "REF-001"))
            .commodity(Commodity.builder().name("Live bovine animals").build())
            .transport(Transport.builder()
                .portOfEntry("GBFXT")
                .arrivalDate(LocalDate.of(2026, Month.APRIL, 22))
                .meansOfTransport(MeansOfTransport.VESSEL)
                .transportIdentification("Vessel Poseidon, voyage 42")
                .transportDocumentReference("BILL-OF-LADING-001")
                .transitedCountries(null)
                .build())
            .build();

        Notification updated = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(updateDto)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.getTransport().getMeansOfTransport()).isEqualTo(MeansOfTransport.VESSEL);
        assertThat(updated.getTransport().getTransitedCountries()).isNullOrEmpty();

        Notification persisted = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(persisted.getTransport().getTransitedCountries()).isNullOrEmpty();
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
    void amend_shouldTransitionStatusFromSubmittedToAmend() {
        // Given — submitted notification
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        // When
        Notification amended = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then
        assertThat(amended).isNotNull();
        assertThat(amended.getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(amended.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(amended.getUpdated()).isNotNull();
    }

    @Test
    void amend_shouldPersistSubmittedBaselineInMongo() {
        // Given — submitted notification with identifiable content
        String referenceNumber = createAndSubmitNotificationWithFullContent();
        Notification beforeAmend = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();

        // When
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange()
            .expectStatus().isOk();

        // Then — baseline captured in Mongo, separate from live content
        Notification reloaded = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(reloaded.getSubmittedBaseline()).isNotNull();
        assertThat(reloaded.getSubmittedBaseline().getOrigin().getInternalReference())
            .isEqualTo("INTERNAL-DO-NOT-COPY");
        assertThat(reloaded.getSubmittedBaseline().getOrigin()).isNotSameAs(reloaded.getOrigin());
        assertThat(reloaded.getSubmittedBaseline().getCommodity().getName())
            .isEqualTo(beforeAmend.getCommodity().getName());
    }

    @Test
    void amend_shouldWriteOutboxEventWithCorrectEnvelope() {
        // Given — create and submit a notification
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        // When — amend with a trace ID
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-amend-001")
            .exchange()
            .expectStatus().isOk();

        // Then — submit + amend events exist with correct types and versions
        List<OutboxEvent> events = outboxEventRepository.findAll()
            .stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(events.get(0).getAggregateVersion()).isEqualTo(1L);

        OutboxEvent amendEvent = events.get(1);
        assertThat(amendEvent.getAggregateId())
            .isEqualTo(OutboxService.buildAggregateId(referenceNumber));
        assertThat(amendEvent.getEventType())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmissionAmended");
        assertThat(amendEvent.getAggregateVersion()).isEqualTo(2L);
        assertThat(amendEvent.getMetadata().getCorrelationId()).isEqualTo("trace-amend-001");
        assertThat(amendEvent.getMetadata().getSchemaUrl()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMISSION_AMENDED.schemaUrl());
        assertThat(gbnAgIdentifier(amendEvent)).isEqualTo(referenceNumber);
    }

    @Test
    void amend_shouldReturn404_whenReferenceNumberDoesNotExist() {
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void amend_shouldReturn400_whenNotificationNotSubmitted() {
        // Given — draft notification
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("DRAFT"));
    }

    @Test
    void cancelAmend_shouldRestoreBaselineAndRevertStatusInMongo() {
        // Given — submitted notification reloaded from Mongo as the golden baseline
        String referenceNumber = createAndSubmitNotificationWithFullContent();
        Notification submittedInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(submittedInMongo.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);

        // When — start amendment (baseline written to Mongo via the real amend path)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange().expectStatus().isOk();

        Notification inAmendInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(inAmendInMongo.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(inAmendInMongo.getSubmittedBaseline()).isNotNull();
        assertAmendableContentMatches(submittedInMongo, inAmendInMongo.getSubmittedBaseline());

        // Simulate trader edits persisted to Mongo during AMEND
        inAmendInMongo.getOrigin().setInternalReference("EDITED-REF");
        inAmendInMongo.getOrigin().setCountryCode("FR");
        inAmendInMongo.getCommodity().setName("Changed commodity");
        inAmendInMongo.setReasonForImport("changedReason");
        inAmendInMongo.setCphNumber("99/999/9999");
        notificationRepository.save(inAmendInMongo);

        Notification editedInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(editedInMongo.getOrigin().getInternalReference()).isEqualTo("EDITED-REF");
        assertThat(editedInMongo.getSubmittedBaseline()).isNotNull();

        // When — cancel amendment via API
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — reload from Mongo: status reverted, baseline cleared, all amendable fields restored
        Notification restoredInMongo = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        assertThat(restoredInMongo.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(restoredInMongo.getSubmittedBaseline()).isNull();
        assertAmendableContentMatches(submittedInMongo, restoredInMongo);
    }

    @Test
    void cancelAmend_shouldReturn404_whenReferenceNumberDoesNotExist() {
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    @Test
    void cancelAmend_shouldReturn400_whenNotificationNotInAmendStatus() {
        // Given — submitted notification (not in AMEND)
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", referenceNumber)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("SUBMITTED"));
    }

    @Test
    void cancelAmend_shouldNotWriteOutboxEvent() {
        // Given — notification in AMEND with edited content
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange().expectStatus().isOk();

        long eventsBeforeCancel = outboxEventRepository.count();

        // When
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/cancel-amend", referenceNumber)
            .exchange().expectStatus().isOk();

        // Then — no additional outbox event written
        assertThat(outboxEventRepository.count()).isEqualTo(eventsBeforeCancel);
    }

    @Test
    void submitFromAmend_shouldClearSubmittedBaseline() {
        // Given — notification amended with edited content
        String referenceNumber = createAndSubmitNotificationWithFullContent();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .exchange().expectStatus().isOk();

        Notification inAmend = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        inAmend.getOrigin().setInternalReference("EDITED-AND-KEPT");
        notificationRepository.save(inAmend);
        assertThat(inAmend.getSubmittedBaseline()).isNotNull();

        // When — resubmit amended notification
        Notification resubmitted = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — edited content kept, baseline cleared
        assertThat(resubmitted.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(resubmitted.getOrigin().getInternalReference()).isEqualTo("EDITED-AND-KEPT");

        Notification reloaded = notificationRepository.findByReferenceNumber(referenceNumber).orElseThrow();
        assertThat(reloaded.getSubmittedBaseline()).isNull();
        assertThat(reloaded.getOrigin().getInternalReference()).isEqualTo("EDITED-AND-KEPT");
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
        assertThat(event.getMetadata().getSchemaUrl()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl());
        assertThat(gbnAgIdentifier(event)).isEqualTo(referenceNumber);
        assertThat(event.getActor()).isNull();
        assertThat(event.getStatusChanges()).hasSize(1);
        assertThat(event.getStatusChanges().getFirst().getStatus())
            .isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(event.getStatusChanges().getFirst().getDateChanged()).isNotNull();
        assertThat(event.getStatusChanges().getFirst().getActor()).isNull();
    }

    @Test
    void submit_shouldWriteActorAndStatusChanges_whenActorBodyProvided() {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        var actorBody = Map.of(
            "id", "contact-guid-001",
            "source", "dynamics-contact",
            "userType", "B2C",
            "displayName", "Jane Farmer",
            "organisationId", "org-001"
        );

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(actorBody)
            .exchange()
            .expectStatus().isOk();

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();

        assertThat(event.getActor()).isNotNull();
        assertThat(event.getActor().getId()).isEqualTo("contact-guid-001");
        assertThat(event.getActor().getSource()).isEqualTo("dynamics-contact");
        assertThat(event.getActor().getUserType()).isEqualTo("B2C");
        assertThat(event.getActor().getDisplayName()).isEqualTo("Jane Farmer");
        assertThat(event.getActor().getOrganisationId()).isEqualTo("org-001");
        assertThat(event.getActor().getOnBehalfOfOrganisationId()).isNull();
        assertThat(event.getStatusChanges()).hasSize(1);
        assertThat(event.getStatusChanges().getFirst().getStatus())
            .isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(event.getStatusChanges().getFirst().getActor()).isEqualTo(event.getActor());
    }

    @Test
    void submitThenAmend_shouldAccumulateActorStatusChanges() {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        var submitActor = Map.of(
            "id", "contact-sub-001", "source", "dynamics-contact",
            "userType", "B2C", "displayName", "Alice", "organisationId", "org-001");
        var amendActor = Map.of(
            "id", "contact-sub-002", "source", "dynamics-contact",
            "userType", "B2C", "displayName", "Bob", "organisationId", "org-002");

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .bodyValue(submitActor)
            .exchange().expectStatus().isOk();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/amend", referenceNumber)
            .bodyValue(amendActor)
            .exchange().expectStatus().isOk();

        List<OutboxEvent> events = outboxEventRepository.findAll()
            .stream()
            .sorted(java.util.Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();

        assertThat(events).hasSize(2);
        OutboxEvent amendEvent = events.get(1);
        assertThat(amendEvent.getActor().getId()).isEqualTo("contact-sub-002");
        assertThat(amendEvent.getStatusChanges()).hasSize(2);
        assertThat(amendEvent.getStatusChanges().get(0).getStatus())
            .isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(amendEvent.getStatusChanges().get(0).getActor().getId())
            .isEqualTo("contact-sub-001");
        assertThat(amendEvent.getStatusChanges().get(1).getStatus())
            .isEqualTo(NotificationStatus.AMEND);
        assertThat(amendEvent.getStatusChanges().get(1).getActor().getId())
            .isEqualTo("contact-sub-002");
    }

    @SuppressWarnings("unchecked")
    private static Object gbnAgIdentifier(OutboxEvent event) {
        Map<String, Object> exchangedDocument =
            (Map<String, Object>) event.getData().get("exchangedDocument");
        return exchangedDocument.get("identifier");
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
    void findAllReferenceNumbers_shouldReturnEmptyPage_whenNoNotificationsExist() {
        // When
        ReferenceNumberPageResponse page = findAllReferenceNumbersPage();

        // Then
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.page()).isZero();
    }

    @Test
    void findAllReferenceNumbers_shouldPageOnlyReferenceNumbersAcrossPages() {
        // Given — create three notifications; page-size is 2 in integration profile
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

        String ref3 = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("FR", "Live pigs"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        // When — fetch page 0 + page 1 + page 2
        ReferenceNumberPageResponse page0 = findAllReferenceNumbersPage(0);
        ReferenceNumberPageResponse page1 = findAllReferenceNumbersPage(1);
        ReferenceNumberPageResponse page2 = findAllReferenceNumbersPage(2);

        // Then
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.content()).hasSize(1);
        assertThat(page1.content()).hasSize(1);
        assertThat(page2.content()).hasSize(1);
        assertThat(page0.content().size() + page1.content().size()
            + page2.content().size()).isEqualTo(3);
        
        // All three refs returned across the three pages, no duplicates
        List<String> allReferenceNumbers = Stream.of(page0, page1, page2)
            .map(ReferenceNumberPageResponse::content)
            .flatMap(Collection::stream)
            .toList();
        assertThat(allReferenceNumbers).containsExactlyInAnyOrder(ref1, ref2, ref3);
    }

    @Test
    void findAllReferenceNumbers_shouldReturnEmptyContentForOutOfRangePage() {
        // Given — one notification (page-size 2 ⇒ totalPages 1)
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("GB", "Live cattle"))
            .exchange().expectStatus().isOk();

        // When — request a page beyond the last
        ReferenceNumberPageResponse page = findAllReferenceNumbersPage(5);

        // Then — empty content, but totals still reflect the real data
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
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

    private ReferenceNumberPageResponse findAllReferenceNumbersPage() {
        return findAllReferenceNumbersPage(0);
    }

    private ReferenceNumberPageResponse findAllReferenceNumbersPage(int page) {
        return webClient("NoAuth")
            .get()
            .uri(NOTIFICATION_ENDPOINT + "/reference-numbers?page=" + page)
            .exchange()
            .expectStatus().isOk()
            .expectBody(ReferenceNumberPageResponse.class)
            .returnResult().getResponseBody();
    }

    private NotificationPageResponse findAllNotificationsPage() {
        return findAllNotificationsPage(1);
    }

    private NotificationPageResponse findAllNotificationsPage(int page) {
        return findAllNotificationsPage(page, null);
    }

    private NotificationPageResponse findAllNotificationsPage(int page, String sort) {
        String uri = NOTIFICATION_ENDPOINT + "?page=" + page;
        if (sort != null) {
            uri += "&sort=" + sort;
        }
        return webClient("NoAuth")
            .get()
            .uri(uri)
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
            .extracting(
                Transport::getPortOfEntry,
                Transport::getArrivalDate,
                Transport::getMeansOfTransport,
                Transport::getTransportIdentification,
                Transport::getTransportDocumentReference,
                Transport::getTransitedCountries)
            .containsExactly(
                "GBFXT",
                LocalDate.of(2026, Month.APRIL, 22),
                MeansOfTransport.RAILWAY,
                "Train 4471, wagon 12",
                "CIM-CONSIGNMENT-001",
                List.of("FR", "DE"));
    }

    private void assertAmendableContentMatches(Notification expected, Notification actual) {
        assertThat(actual.getOrigin()).isEqualTo(expected.getOrigin());
        assertThat(actual.getReasonForImport()).isEqualTo(expected.getReasonForImport());
        assertThat(actual.getCommodity()).isEqualTo(expected.getCommodity());
        assertThat(actual.getAdditionalDetails()).isEqualTo(expected.getAdditionalDetails());
        assertThat(actual.getPlaceOfOrigin()).isEqualTo(expected.getPlaceOfOrigin());
        assertThat(actual.getConsignor()).isEqualTo(expected.getConsignor());
        assertThat(actual.getConsignee()).isEqualTo(expected.getConsignee());
        assertThat(actual.getImporter()).isEqualTo(expected.getImporter());
        assertThat(actual.getDestination()).isEqualTo(expected.getDestination());
        assertThat(actual.getConsignment()).isEqualTo(expected.getConsignment());
        assertThat(actual.getCphNumber()).isEqualTo(expected.getCphNumber());
        assertThat(actual.getTransport()).isEqualTo(expected.getTransport());
    }

    private void assertAmendableContentMatches(Notification expected, NotificationContentSnapshot actual) {
        assertThat(actual.getOrigin()).isEqualTo(expected.getOrigin());
        assertThat(actual.getReasonForImport()).isEqualTo(expected.getReasonForImport());
        assertThat(actual.getCommodity()).isEqualTo(expected.getCommodity());
        assertThat(actual.getAdditionalDetails()).isEqualTo(expected.getAdditionalDetails());
        assertThat(actual.getPlaceOfOrigin()).isEqualTo(expected.getPlaceOfOrigin());
        assertThat(actual.getConsignor()).isEqualTo(expected.getConsignor());
        assertThat(actual.getConsignee()).isEqualTo(expected.getConsignee());
        assertThat(actual.getImporter()).isEqualTo(expected.getImporter());
        assertThat(actual.getDestination()).isEqualTo(expected.getDestination());
        assertThat(actual.getConsignment()).isEqualTo(expected.getConsignment());
        assertThat(actual.getCphNumber()).isEqualTo(expected.getCphNumber());
        assertThat(actual.getTransport()).isEqualTo(expected.getTransport());
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

    @Test
    void copy_shouldCreateNewDraftFromSourceNotification() {
        // Given — create a source notification with a full set of fields
        NotificationDto sourceDto = sourceNotificationWithAllOperators();

        Notification source = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(sourceDto)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        assertThat(source).isNotNull();
        String sourceRef = source.getReferenceNumber();

        // When — copy via the dedicated copy endpoint
        Notification copy = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT + "/{ref}/copy", sourceRef)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — new reference number, DRAFT status
        assertThat(copy).isNotNull();
        assertThat(copy.getReferenceNumber()).isNotEqualTo(sourceRef);
        assertThat(copy.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertThat(copy.getStatus()).isEqualTo(NotificationStatus.DRAFT);

        // Retained fields
        assertThat(copy.getOrigin().getCountryCode()).isEqualTo("DE");
        assertThat(copy.getOrigin().getRequiresRegionCode()).isEqualTo("yes");
        assertThat(copy.getReasonForImport()).isEqualTo("internalMarket");
        assertThat(copy.getCommodity().getName()).isEqualTo("Live bovine animals");
        assertThat(copy.getAdditionalDetails().getCertifiedFor()).isEqualTo("Breeding");
        assertThat(copy.getCphNumber()).isEqualTo("12/345/6789");

        // Excluded fields
        assertThat(copy.getOrigin().getInternalReference()).isNull();
        assertThat(copy.getAdditionalDetails().getUnweanedAnimals()).isNull();
        assertThat(copy.getTransport()).isNull();
        assertThat(copy.getConsignment()).isNull();
        CommodityComplement cc = copy.getCommodity().getCommodityComplement().getFirst();
        assertThat(cc.getTypeOfCommodity()).isEqualTo("LIVE");
        assertThat(cc.getTotalNoOfAnimals()).isNull();
        assertThat(cc.getTotalNoOfPackages()).isNull();
        assertThat(cc.getSpecies()).isNull();

        // Original unchanged
        Notification original = notificationRepository.findByReferenceNumber(sourceRef).orElseThrow();
        assertThat(original.getOrigin().getInternalReference()).isEqualTo("INTERNAL-DO-NOT-COPY");
        assertThat(original.getAdditionalDetails().getUnweanedAnimals()).isEqualTo("yes");
        assertThat(original.getTransport().getPortOfEntry()).isEqualTo("GBDVR");
    }

    @Test
    void copy_shouldRetainAllOperatorAddresses() {
        NotificationDto sourceDto = sourceNotificationWithAllOperators();

        Notification source = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT).bodyValue(sourceDto)
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult().getResponseBody();

        assertThat(source).isNotNull();

        Notification copy = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/copy", source.getReferenceNumber())
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult().getResponseBody();

        assertThat(copy).isNotNull();
        assertThat(copy.getPlaceOfOrigin()).isEqualTo(NotificationTestData.placesOfOrigin().getFirst());
        assertThat(copy.getConsignor()).isEqualTo(NotificationTestData.consignors().getFirst());
        assertThat(copy.getConsignee()).isEqualTo(NotificationTestData.consignees().getFirst());
        assertThat(copy.getImporter()).isEqualTo(NotificationTestData.importers().getFirst());
        assertThat(copy.getDestination()).isEqualTo(NotificationTestData.destinations().getFirst());
    }

    @Test
    void copy_shouldReturn400_whenSourceNotificationIsDeleted() {
        // Given — create and soft-delete a source notification
        String sourceRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("DE", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/soft-delete", sourceRef)
            .exchange().expectStatus().isOk();

        // When — attempt to copy via the dedicated copy endpoint
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/copy", sourceRef)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void copy_shouldCreateNewDraftFromSubmittedNotification() {
        // Given — create a notification and submit it
        String sourceRef = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto("IE", "Live cattle"))
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", sourceRef)
            .exchange().expectStatus().isOk();

        // When — copy the submitted notification
        Notification copy = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/copy", sourceRef)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        // Then — copy is a new DRAFT with a different reference number
        assertThat(copy).isNotNull();
        assertThat(copy.getReferenceNumber()).isNotEqualTo(sourceRef);
        assertThat(copy.getReferenceNumber()).matches(REF_FORMAT_REGEX);
        assertThat(copy.getStatus()).isEqualTo(NotificationStatus.DRAFT);
    }

    @Test
    void copy_shouldReturn404_whenSourceNotificationDoesNotExist() {
        // When / Then
        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/copy", NONEXISTENT_REF)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value(Matchers.containsString(NONEXISTENT_REF));
    }

    private String createAndSubmitNotificationWithFullContent() {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(sourceNotificationWithAllOperators())
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .exchange().expectStatus().isOk();

        return referenceNumber;
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

    private NotificationDto sourceNotificationWithAllOperators() {
        CommodityComplement complement = new CommodityComplement("LIVE", 10, 5,
            List.of(NotificationTestData.species()));
        return NotificationDto.builder()
            .origin(new Origin("DE", "yes", "INTERNAL-DO-NOT-COPY"))
            .commodity(Commodity.builder()
                .name("Live bovine animals")
                .commodityComplement(List.of(complement))
                .build())
            .reasonForImport("internalMarket")
            .additionalDetails(new AdditionalDetails("Breeding", "yes"))
            .placeOfOrigin(NotificationTestData.placesOfOrigin().getFirst())
            .consignor(NotificationTestData.consignors().getFirst())
            .consignee(NotificationTestData.consignees().getFirst())
            .importer(NotificationTestData.importers().getFirst())
            .destination(NotificationTestData.destinations().getFirst())
            .consignment(NotificationTestData.consignments().getFirst())
            .cphNumber("12/345/6789")
            .transport(Transport.builder()
                .portOfEntry("GBDVR")
                .arrivalDate(LocalDate.of(2026, Month.JUNE, 1))
                .build())
            .build();
    }

    private LocalDate extractArrivalDate(NotificationDto notification) {
        if (notification.getTransport() == null) {
            return null;
        }
        return notification.getTransport().getArrivalDate();
    }
}
