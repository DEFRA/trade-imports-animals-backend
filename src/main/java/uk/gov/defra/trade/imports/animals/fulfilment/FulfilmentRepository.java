package uk.gov.defra.trade.imports.animals.fulfilment;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FulfilmentRepository extends MongoRepository<Fulfilment, String> {

    Optional<Fulfilment> findByOwnerSubAndOwnerOrganisationAndCopyIdempotencyKey(
        String ownerSub,
        String ownerOrganisation,
        String copyIdempotencyKey);
}
