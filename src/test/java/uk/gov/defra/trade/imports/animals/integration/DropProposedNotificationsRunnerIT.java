package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.cleanup.DropProposedNotificationsRunner;

class DropProposedNotificationsRunnerIT extends IntegrationBase {

    private static final String COLLECTION = "proposed_notifications";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private DropProposedNotificationsRunner runner;

    @BeforeEach
    void setUp() {
        if (mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.dropCollection(COLLECTION);
        }
    }

    @Test
    void run_shouldDropTheCollectionWhenItExists() {
        mongoTemplate.getCollection(COLLECTION).insertOne(new Document("_id", "seed"));
        assertThat(mongoTemplate.collectionExists(COLLECTION)).isTrue();

        runner.run(null);

        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }

    @Test
    void run_shouldBeIdempotentWhenTheCollectionIsAlreadyGone() {
        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();

        runner.run(null);

        assertThat(mongoTemplate.collectionExists(COLLECTION)).isFalse();
    }
}
