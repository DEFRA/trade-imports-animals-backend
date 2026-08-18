package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;

/**
 * Integration test for optimistic-locking behaviour on {@link Notification}.
 *
 * <p>Uses a real Testcontainers MongoDB instance (inherited from {@link IntegrationBase}) to
 * verify that Spring Data MongoDB honours the {@code @Version} annotation on the entity: a
 * second save from an in-memory copy holding the pre-write version must fail rather than
 * silently overwrite the concurrent update.
 *
 * <p>Guards the mid-air-collision protection introduced under EUDPA-314. Without {@code @Version}
 * the second save would last-write-wins and the test would fail on the missing exception.
 */
class OptimisticLockingIT extends IntegrationBase {

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    /**
     * Two in-memory copies of the same notification each hold version 0 after the initial save;
     * the first re-save advances the stored version to 1, so the second re-save (still carrying
     * version 0) must be rejected with {@link OptimisticLockingFailureException}.
     */
    @Test
    void save_shouldThrowOptimisticLockingFailure_whenSavingWithStaleVersion() {
        // Arrange — persist a notification and load two in-memory copies of it (simulating
        // two tabs / two pods that each read before either wrote).
        Notification seed = new Notification();
        seed.setReferenceNumber("GBN-AG-26-VER001");
        seed.setStatus(NotificationStatus.DRAFT);
        seed.setCreated(LocalDateTime.now());
        seed.setUpdated(LocalDateTime.now());
        Notification saved = notificationRepository.save(seed);

        Notification tabA = notificationRepository.findById(saved.getId()).orElseThrow();
        Notification tabB = notificationRepository.findById(saved.getId()).orElseThrow();

        // Act — tab A commits first, advancing the stored version.
        tabA.setStatus(NotificationStatus.SUBMITTED);
        notificationRepository.save(tabA);

        // Assert — tab B still holds the pre-write version; its save must be rejected.
        tabB.setStatus(NotificationStatus.AMEND);
        assertThatThrownBy(() -> notificationRepository.save(tabB))
            .isInstanceOf(OptimisticLockingFailureException.class);

        // And the stored notification reflects tab A's write, not tab B's.
        Notification stored = notificationRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
    }
}
