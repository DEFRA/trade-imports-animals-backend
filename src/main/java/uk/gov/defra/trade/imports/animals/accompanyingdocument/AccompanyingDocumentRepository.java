package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccompanyingDocumentRepository
    extends MongoRepository<AccompanyingDocument, String> {

  List<AccompanyingDocument> findAllByNotificationReferenceNumber(String referenceNumber);

  Optional<AccompanyingDocument> findByUploadId(String uploadId);

  Optional<AccompanyingDocument> findFirstByNotificationReferenceNumberAndScanStatus(
      String notificationReferenceNumber, ScanStatus scanStatus);

  void deleteAllByNotificationReferenceNumberIn(List<String> referenceNumbers);
}
