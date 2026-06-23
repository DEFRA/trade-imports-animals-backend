package uk.gov.defra.trade.imports.animals.outbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxEventRepository extends MongoRepository<OutboxEvent, String> {

    Optional<OutboxEvent> findTopByAggregateIdOrderByAggregateVersionDesc(String aggregateId);

    List<OutboxEvent> findAllByAggregateIdOrderByAggregateVersionAsc(String aggregateId);

    List<OutboxEvent> findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(Pageable pageable);
}
