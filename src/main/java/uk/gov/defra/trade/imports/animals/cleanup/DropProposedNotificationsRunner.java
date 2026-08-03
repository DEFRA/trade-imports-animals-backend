package uk.gov.defra.trade.imports.animals.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Transitional cleanup: drops the legacy {@code proposed_notifications} collection on startup.
 *
 * <p>The Mapper B projection was removed when the EUDPA-288 persistence decision landed on
 * the single current-notification projection. This runner leaves no orphan data behind once
 * each environment has booted at least once on this change.
 *
 * <p>TODO: delete this class and its integration test once every environment (dev, test,
 * perf-test, pre-prod, prod) has booted at least once on this change.
 */
@Component
@Slf4j
public class DropProposedNotificationsRunner implements ApplicationRunner {

    static final String COLLECTION = "proposed_notifications";

    private final MongoTemplate mongoTemplate;

    public DropProposedNotificationsRunner(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
            log.info("Dropped legacy {} collection", COLLECTION);
        }
    }
}
