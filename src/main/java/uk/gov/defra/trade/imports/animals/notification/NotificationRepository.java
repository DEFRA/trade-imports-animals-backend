package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    Optional<Notification> findByReferenceNumber(String referenceNumber);

    boolean existsByReferenceNumber(String referenceNumber);

    List<NotificationReferenceOnly> findAllByReferenceNumberIn(List<String> referenceNumbers);

    Page<NotificationReferenceOnly> findAllProjectedBy(Pageable pageable);

    Page<Notification> findAllByOwnerSubAndOwnerOrganisationAndStatusIn(
        String ownerSub,
        String ownerOrganisation,
        List<NotificationStatus> statuses,
        Pageable pageable);

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);

}
