package uk.gov.defra.trade.imports.animals.outbox;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface OutboxEventRepository extends MongoRepository<OutboxEvent, String> {

    @Query(value = "{ 'aggregateId': ?0 }",
        sort = "{ 'aggregateVersion': -1 }",
        fields = "{ 'aggregateVersion': 1 }")
    Optional<OutboxEvent> findTopByAggregateIdOrderByAggregateVersionDesc(String aggregateId);
}
