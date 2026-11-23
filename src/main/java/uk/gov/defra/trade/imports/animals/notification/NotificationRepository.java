package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<NotificationAggregate, String> {

    boolean existsByReferenceNumber(String referenceNumber);

    Optional<NotificationAggregate> findByReferenceNumberAndOrganisationId(
        String referenceNumber, String organisationId);

    List<NotificationReferenceOnly> findAllReferencesByReferenceNumberIn(List<String> referenceNumbers);

    @Query(
        value = "{ 'referenceNumber': ?0, 'organisationId': ?1 }",
        fields = "{ 'referenceNumber': 1, 'concurrencyToken': 1, 'status': 1, 'created': 1, "
            + "'submittedAt': 1, 'fulfilments': 1, 'answersDifferFromSubmitted': 1 }")
    Optional<NotificationAggregate> findFulfilmentFieldsByReferenceNumberAndOrganisationId(
        String referenceNumber, String organisationId);

    Optional<NotificationView> findViewByReferenceNumberAndStatusInAndOrganisationId(
        String referenceNumber, List<NotificationStatus> statuses, String organisationId);

    Page<NotificationView> findAllViewByStatusInAndOrganisationId(
        List<NotificationStatus> statuses, String organisationId, Pageable pageable);

    Optional<NotificationExpiry> findExpiryByReferenceNumber(String referenceNumber);

    Page<NotificationReferenceOnly> findAllReferencesBy(Pageable pageable);

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);

}
