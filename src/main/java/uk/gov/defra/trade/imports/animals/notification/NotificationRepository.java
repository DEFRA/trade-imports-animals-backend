package uk.gov.defra.trade.imports.animals.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    Optional<Notification> findByReferenceNumber(String referenceNumber);

    List<NotificationReferenceOnly> findAllByReferenceNumberIn(List<String> referenceNumbers);

    Optional<NotificationFulfilmentsView> findFulfilmentsViewByReferenceNumber(String referenceNumber);

    Optional<NotificationView> findViewByReferenceNumberAndStatusIn(
        String referenceNumber, List<NotificationStatus> statuses);

    Page<NotificationView> findAllViewByStatusIn(List<NotificationStatus> statuses, Pageable pageable);

    /**
     * Notifications due for automatic expiry: a non-null {@code expireAt} at or before {@code now}.
     * The explicit {@code $ne: null} clause guarantees pre-existing notifications (which have no
     * {@code expireAt}) are never selected. Expressed as a {@code @Query} because a derived
     * {@code findByExpireAtNotNullAndExpireAtLessThanEqual} name puts two criteria on the same
     * field, which Spring Data's derived-query builder rejects.
     */
    @Query("{ 'expireAt': { $ne: null, $lte: ?0 } }")
    List<NotificationReferenceOnly> findExpired(LocalDateTime now, Pageable pageable);

    Page<NotificationReferenceOnly> findAllProjectedBy(Pageable pageable);

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);

}
