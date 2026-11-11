package uk.gov.defra.trade.imports.animals.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationTemplateRepository
    extends MongoRepository<NotificationTemplate, String> {

    List<NotificationTemplate> findAllByOwnerIdAndStatusNotOrderBySavedDescCreatedDesc(
        String ownerId, TemplateStatus status);

    Optional<NotificationTemplate> findByIdAndOwnerId(String id, String ownerId);
}
