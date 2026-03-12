package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.Origin;

@Slf4j
class NotificationIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    @Test
    void post_shouldCreateNewNotification() {
        // Given
        Notification notification = createNotification("GB", "Live bovine animals");

        // When
        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification)
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
        assertThat(created.getCommodity()).isEqualTo("Live bovine animals");
    }

    @Test
    void post_shouldCreateNotificationWithOriginDetails() {
        // Given
        Origin origin = new Origin("IE", "true", "REF-001");
        Notification notification = new Notification();
        notification.setOrigin(origin);
        notification.setCommodity("Live cattle");

        // When
        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification)
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
        assertThat(created.getCommodity()).isEqualTo("Live cattle");
    }

    @Test
    void post_shouldUpdateExistingNotification() {
        // Given - create initial notification
        Notification initial = createNotification("FR", "Live sheep");
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
        Notification update = new Notification();
        update.setId(createdId);
        update.setReferenceNumber(referenceNumber);
        update.setOrigin(new Origin("ES", "false", "REF-002"));
        update.setCommodity("Live pigs");

        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(update)
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
        assertThat(updated.getCommodity()).isEqualTo("Live pigs");

        // Verify only one notification exists
        assertThat(findAllNotifications()).hasSize(1);
    }

    @Test
    void findAll_shouldReturnAllNotifications() {
        // Given - create multiple notifications
        Notification notification1 = createNotification("GB", "Live cattle");
        Notification notification2 = createNotification("IE", "Live sheep");
        Notification notification3 = createNotification("FR", "Live pigs");

        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(notification1).exchange();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(notification2).exchange();
        webClient("NoAuth").post().uri(NOTIFICATION_ENDPOINT).bodyValue(notification3).exchange();

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
        Notification notification1 = createNotification("GB", "Live cattle");
        Notification notification2 = createNotification("IE", "Live sheep");
        Notification notification3 = createNotification("FR", "Live pigs");

        // When - save all notifications
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification1)
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification2)
            .exchange()
            .expectStatus().isOk();

        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification3)
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
        Notification notification1 = createNotification("GB", "Live cattle");
        EntityExchangeResult<Notification> createResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification1)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        String referenceNumber = createResult.getResponseBody().getReferenceNumber();

        // When - attempt to create second notification with same referenceNumber
        Notification notification2 = createNotification("IE", "Live sheep");
        notification2.setReferenceNumber(referenceNumber);

        // Then - expect updated notification
        webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification2)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Verify only one notification was saved
        List<Notification> allNotifications = findAllNotifications();
        assertThat(allNotifications).hasSize(1);
        assertThat(allNotifications.getFirst().getReferenceNumber()).isEqualTo(referenceNumber);
        assertThat(allNotifications.getFirst().getOrigin().getCountryCode()).isEqualTo("IE");
        assertThat(allNotifications.getFirst().getCommodity()).isEqualTo("Live sheep");
    }

    @Test
    void fullCrudFlow_shouldWorkEndToEnd() {
        // 1. Create notification
        Notification createNotification = createNotification("NL", "Live horses");
        EntityExchangeResult<Notification> createResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotification)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        Notification created = createResult.getResponseBody();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).isNotNull();
        assertThat(created.getReferenceNumber()).startsWith("DRAFT.IMP.");
        assertThat(created.getOrigin().getCountryCode()).isEqualTo("NL");
        assertThat(created.getCommodity()).isEqualTo("Live horses");

        // 2. Verify findAll returns the created notification
        List<Notification> allNotifications = findAllNotifications();
        assertThat(allNotifications).hasSize(1);
        assertThat(allNotifications.get(0).getId()).isEqualTo(created.getId());

        // 3. Update the notification
        Notification updateNotification = new Notification();
        updateNotification.setId(created.getId());
        updateNotification.setReferenceNumber(created.getReferenceNumber());
        updateNotification.setOrigin(new Origin("BE", "false", "REF-BE-001"));
        updateNotification.setCommodity("Live donkeys");

        EntityExchangeResult<Notification> updateResult = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(updateNotification)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        Notification updated = updateResult.getResponseBody();
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getReferenceNumber()).isEqualTo(created.getReferenceNumber());
        assertThat(updated.getOrigin().getCountryCode()).isEqualTo("BE");
        assertThat(updated.getCommodity()).isEqualTo("Live donkeys");

        // 4. Verify only one notification exists
        assertThat(findAllNotifications()).hasSize(1);

        // 5. Verify the notification was updated in database
        Notification persisted = notificationRepository.findById(created.getId()).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getOrigin().getCountryCode()).isEqualTo("BE");
        assertThat(persisted.getCommodity()).isEqualTo("Live donkeys");
    }

    @Test
    void post_shouldGenerateReferenceNumberAutomatically() {
        // Given
        Notification notification = createNotification("DE", "Live goats");

        // When
        EntityExchangeResult<Notification> result = webClient("NoAuth")
            .post()
            .uri(NOTIFICATION_ENDPOINT)
            .bodyValue(notification)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Notification.class)
            .returnResult();

        // Then - verify reference number format
        Notification created = result.getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getReferenceNumber()).isNotNull();

        // Reference number should follow pattern: DRAFT.IMP.YYYY.<id>
        assertThat(created.getReferenceNumber()).matches("DRAFT\\.IMP\\.\\d{4}\\..+");
        assertThat(created.getReferenceNumber()).contains(created.getId());

        log.info("Notification ID: {}", created.getId());
        log.info("Generated reference number: {}", created.getReferenceNumber());
    }

    @Test
    void post_shouldHandleDifferentCommodityTypes() {
        // Given - create notifications with different commodity types
        Notification cattle = createNotification("GB", "Live bovine animals");
        Notification sheep = createNotification("IE", "Live ovine animals");
        Notification pigs = createNotification("FR", "Live porcine animals");

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
            .extracting(Notification::getCommodity)
            .containsExactlyInAnyOrder(
                "Live bovine animals",
                "Live ovine animals",
                "Live porcine animals"
            );
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

    private Notification createNotification(String countryCode, String commodity) {
        Origin origin = new Origin();
        origin.setCountryCode(countryCode);

        Notification notification = new Notification();
        notification.setOrigin(origin);
        notification.setCommodity(commodity);

        return notification;
    }
}
