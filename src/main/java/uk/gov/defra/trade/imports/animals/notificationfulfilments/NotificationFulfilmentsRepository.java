package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationFulfilmentsRepository extends MongoRepository<NotificationFulfilments, String> {

    Optional<NotificationFulfilments> findByIdempotencyKey(String idempotencyKey);
}
