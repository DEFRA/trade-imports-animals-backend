package uk.gov.defra.trade.imports.animals.fulfilment;

import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FulfilmentRepository extends MongoRepository<Fulfilment, String> {

    Page<Fulfilment> findAllByOwnerSubAndOwnerOrganisationAndStatusIn(
        String ownerSub,
        String ownerOrganisation,
        Collection<FulfilmentStatus> statuses,
        Pageable pageable);
}
