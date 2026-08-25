package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

class OptimisticLockingIT extends IntegrationBase {

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    @Test
    void save_shouldThrowOptimisticLockingFailure_whenSavingWithStaleConcurrencyToken() {
        // Arrange — persist a notification and load two in-memory copies of it (simulating
        // two tabs / two pods that each read before either wrote).
        LocalDateTime now = LocalDateTime.now();

        NotificationAggregate seed = new NotificationAggregate();
        seed.setReferenceNumber("GBN-AG-26-VER001");
        seed.setStatus(NotificationStatus.DRAFT);
        seed.setCreated(now);
        seed.setUpdated(now);

        NotificationAggregate saved = notificationRepository.save(seed);
        NotificationAggregate notificationWithUpdate = notificationRepository.findById(saved.getId()).orElseThrow();
        NotificationAggregate staleNotification = notificationRepository.findById(saved.getId()).orElseThrow();

        // Act — tab A commits first, advancing the stored concurrencyToken.
        notificationWithUpdate.setStatus(NotificationStatus.SUBMITTED);
        notificationRepository.save(notificationWithUpdate);

        // Assert — tab B still holds the pre-write concurrencyToken; its save must be rejected.
        staleNotification.setStatus(NotificationStatus.AMEND);
        assertThatThrownBy(() -> notificationRepository.save(staleNotification))
            .isInstanceOf(OptimisticLockingFailureException.class);

        // And the stored notification reflects tab A's write, not tab B's.
        NotificationAggregate stored = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
    }
}
