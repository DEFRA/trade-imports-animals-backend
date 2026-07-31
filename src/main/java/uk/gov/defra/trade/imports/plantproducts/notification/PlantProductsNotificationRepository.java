package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface PlantProductsNotificationRepository
    extends MongoRepository<PlantProductsNotification, String> {

    Optional<PlantProductsNotification> findByReferenceNumber(String referenceNumber);

    Optional<PlantProductsNotification> findByReferenceNumberAndStatusIn(
        String referenceNumber, Collection<PlantProductsNotificationStatus> statuses);

    Page<PlantProductsNotification> findAllByStatusIn(
        Collection<PlantProductsNotificationStatus> statuses, Pageable pageable);

    @Query("{ 'expireAt': { $ne: null, $lte: ?0 } }")
    List<PlantProductsNotificationReferenceOnly> findExpired(LocalDateTime now, Pageable pageable);

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);
}
