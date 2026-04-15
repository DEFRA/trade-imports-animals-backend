package uk.gov.defra.trade.imports.animals.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {

    Optional<Notification> findByReferenceNumber(String referenceNumber);

    List<NotificationReferenceOnly> findAllByReferenceNumberIn(List<String> referenceNumbers);

    List<NotificationReferenceOnly> findAllProjectedBy();

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);

}
