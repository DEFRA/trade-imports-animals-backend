package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;

class NotificationProjectionIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String REF = "GBN-AG-26-ABC123";
    private static final String OTHER_REF = "GBN-AG-26-ABC124";

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    @Test
    void putNotification_shouldCreateCurrentProjectionForClientKnownId() {
        NotificationDto dto = currentNotification(REF, "PERMANENT", "GB");

        Notification created = webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueEquals(
                "Location", "http://localhost:8085/notifications/" + REF)
            .expectBody(Notification.class)
            .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.getReferenceNumber()).isEqualTo(REF);
        assertThat(created.getReasonForImport()).isEqualTo("PERMANENT");
        assertThat(created.getOrigin().getCountryCode()).isEqualTo("GB");
        assertThat(created.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(created.getCreated()).isNotNull();
        assertThat(created.getUpdated()).isNotNull();
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void putNotification_shouldWholeReplaceAndAllowIdempotentRetry() {
        putCurrentNotification(
            currentNotification(REF, "PERMANENT", "GB"), true);

        NotificationDto replacement = currentNotification(REF, "SHOW", "FR");
        replacement.setCphNumber("12/345/6789");

        Notification replaced = putCurrentNotification(replacement, false);
        Notification persistedAfterReplace =
            notificationRepository.findByReferenceNumber(REF).orElseThrow();
        Notification retried = putCurrentNotification(replacement, false);
        Notification persistedAfterRetry =
            notificationRepository.findByReferenceNumber(REF).orElseThrow();

        assertThat(replaced.getId()).isEqualTo(retried.getId());
        assertThat(retried.getReasonForImport()).isEqualTo("SHOW");
        assertThat(retried.getOrigin().getCountryCode()).isEqualTo("FR");
        assertThat(retried.getCphNumber()).isEqualTo("12/345/6789");
        assertThat(persistedAfterRetry).isEqualTo(persistedAfterReplace);
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void putNotification_shouldReturn400_whenPathAndBodyReferenceNumbersDiffer() {
        webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{id}", REF)
            .bodyValue(currentNotification(OTHER_REF, "SHOW", "FR"))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.detail").value(Matchers.containsString("must match"));

        assertThat(notificationRepository.count()).isZero();
    }

    private Notification putCurrentNotification(NotificationDto dto, boolean created) {
        var response = webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{id}", dto.getReferenceNumber())
            .bodyValue(dto)
            .exchange();
        if (created) {
            response.expectStatus().isCreated();
        } else {
            response.expectStatus().isOk();
        }
        return response.expectBody(Notification.class)
            .returnResult().getResponseBody();
    }

    private NotificationDto currentNotification(
        String referenceNumber, String reasonForImport, String countryCode) {
        return NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .reasonForImport(reasonForImport)
            .origin(new Origin(countryCode, "false", "PROJECTION-REF"))
            .build();
    }
}
