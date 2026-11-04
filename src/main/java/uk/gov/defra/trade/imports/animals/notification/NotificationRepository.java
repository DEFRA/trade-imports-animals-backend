package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<NotificationAggregate, String> {

    Optional<NotificationAggregate> findByReferenceNumber(String referenceNumber);

    List<NotificationReferenceOnly> findAllByReferenceNumberIn(List<String> referenceNumbers);

    Optional<NotificationFulfilmentsView> findFulfilmentsViewByReferenceNumber(String referenceNumber);

    Optional<NotificationView> findViewByReferenceNumberAndStatusIn(
        String referenceNumber, List<NotificationStatus> statuses);

    Page<NotificationView> findAllViewByStatusIn(List<NotificationStatus> statuses, Pageable pageable);

    Optional<NotificationExpiry> findExpiryByReferenceNumber(String referenceNumber);

    Page<NotificationReferenceOnly> findAllProjectedBy(Pageable pageable);

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);

}
