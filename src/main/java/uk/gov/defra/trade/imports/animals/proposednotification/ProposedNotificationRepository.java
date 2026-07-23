package uk.gov.defra.trade.imports.animals.proposednotification;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProposedNotificationRepository
    extends MongoRepository<ProposedNotification, String> {

}
