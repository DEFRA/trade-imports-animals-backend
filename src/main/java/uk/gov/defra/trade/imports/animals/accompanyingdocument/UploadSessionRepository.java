package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UploadSessionRepository extends MongoRepository<UploadSession, String> {

  Optional<UploadSession> findByUploadId(String uploadId);

  Optional<UploadSession> findByCorrelationId(String correlationId);

  Optional<UploadSession> findByNotificationReferenceNumberAndCorrelationId(
      String notificationReferenceNumber, String correlationId);

  void deleteAllByNotificationReferenceNumberIn(List<String> notificationReferenceNumbers);
}
