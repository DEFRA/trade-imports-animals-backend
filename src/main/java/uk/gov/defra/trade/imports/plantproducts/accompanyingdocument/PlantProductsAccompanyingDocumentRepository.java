package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlantProductsAccompanyingDocumentRepository
    extends MongoRepository<PlantProductsAccompanyingDocument, String> {

    List<PlantProductsAccompanyingDocument> findByNotificationReferenceNumber(String referenceNumber);

    Optional<PlantProductsAccompanyingDocument> findByIdAndNotificationReferenceNumber(
        String id, String referenceNumber);

    void deleteByNotificationReferenceNumber(String referenceNumber);
}
