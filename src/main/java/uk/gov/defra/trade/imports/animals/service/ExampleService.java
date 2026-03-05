package uk.gov.defra.trade.imports.animals.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.domain.Example;
import uk.gov.defra.trade.imports.animals.domain.repository.ExampleRepository;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

/**
 * Service layer for Example CRUD operations.
 *
 * Demonstrates CDP compliance:
 * - Structured logging with SLF4J (ECS format)
 * - Custom CloudWatch metrics for business events
 * - Proper exception handling with meaningful messages
 * - MongoDB operations with Spring Data
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExampleService {

    private final ExampleRepository repository;
    private final MeterRegistry meterRegistry;

    /**
     * Create a new example.
     *
     * @param entity the example to create
     * @return the created example with generated ID
     * @throws ConflictException if an example with the same name already exists
     */
    public Example create(Example entity) {
        log.info("Creating example with name: {}", entity.getName());

        // Check for duplicate name
        if (repository.findByName(entity.getName()).isPresent()) {
            log.warn("Example with name '{}' already exists", entity.getName());
            throw new ConflictException("Example with name '" + entity.getName() + "' already exists");
        }

        entity.setCreated(LocalDateTime.now());

        try {
            Example saved = repository.save(entity);
            meterRegistry.counter("example_created").increment();
            log.info("Created example with id: {}", saved.getId());
            return saved;

        } catch (DuplicateKeyException e) {
            // Handle race condition where two requests create same name simultaneously
            log.warn("Duplicate key error creating example: {}", entity.getName());
            throw new ConflictException("Example with name '" + entity.getName() + "' already exists");
        }
    }

    /**
     * Get all examples.
     *
     * @return list of all examples
     */
    public List<Example> findAll() {
        log.debug("Fetching all examples");
        List<Example> examples = repository.findAll();
        log.debug("Found {} examples", examples.size());
        return examples;
    }

    /**
     * Get an example by ID.
     *
     * @param id the example ID
     * @return the example
     * @throws NotFoundException if the example does not exist
     */
    public Example findById(String id) {
        log.debug("Fetching example with id: {}", id);
        return repository.findById(id)
            .orElseThrow(() -> {
                log.warn("Example not found with id: {}", id);
                return new NotFoundException("Example not found with id: " + id);
            });
    }

    /**
     * Update an existing example.
     *
     * @param id     the example ID
     * @param entity the updated example data
     * @return the updated example
     * @throws NotFoundException if the example does not exist
     * @throws ConflictException if the new name conflicts with another example
     */
    public Example update(String id, Example entity) {
        log.info("Updating example with id: {}", id);

        // Check if example exists
        Example existing = findById(id);

        // Check for name conflict with other examples
        if (!existing.getName().equals(entity.getName())) {
            repository.findByName(entity.getName()).ifPresent(conflict -> {
                if (!conflict.getId().equals(id)) {
                    log.warn("Example with name '{}' already exists", entity.getName());
                    throw new ConflictException("Example with name '" + entity.getName() + "' already exists");
                }
            });
        }

        // Update fields
        existing.setName(entity.getName());
        existing.setValue(entity.getValue());
        existing.setCounter(entity.getCounter());

        Example updated = repository.save(existing);
        meterRegistry.counter("example_updated").increment();
        log.info("Updated example with id: {}", updated.getId());
        return updated;
    }

    /**
     * Delete an example.
     *
     * @param id the example ID
     * @throws NotFoundException if the example does not exist
     */
    public void delete(String id) {
        log.info("Deleting example with id: {}", id);

        // Check if example exists
        findById(id);

        repository.deleteById(id);
        meterRegistry.counter("example_deleted").increment();
        log.info("Deleted example with id: {}", id);
    }
}
