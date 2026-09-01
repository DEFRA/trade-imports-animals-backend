package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictAtCommit;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictOnOutboxCreation;

import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the retry advisor is actually applied to {@code @Transactional} beans in the real
 * application context, and applied <em>outside</em> the transaction boundary.
 */
@Import(TransactionRetryAdvisorIT.FlakyServiceConfiguration.class)
class TransactionRetryAdvisorIT extends IntegrationBase {

    private static final String COLLECTION = "transaction_retry_probe";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private FlakyTransactionalService flakyTransactionalService;

    @Autowired
    private CommitFailingTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // Created outside any transaction: creating a collection inside one is the very fault
        // EUDPA-356 is about, and this probe should exercise the retry, not reproduce the cause.
        if (!mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.createCollection(COLLECTION);
        }
        mongoTemplate.remove(new Query(), COLLECTION);
        flakyTransactionalService.reset();
        transactionManager.failNextCommits(0);
    }

    @Test
    void transactionalMethod_shouldSucceed_whenTheFirstAttemptFailsTransiently() {
        flakyTransactionalService.failNextAttempts(1);

        flakyTransactionalService.recordAttempt("probe");

        assertThat(flakyTransactionalService.attempts()).isEqualTo(2);
    }

    @Test
    void transactionalMethod_shouldCommitOnlyTheSuccessfulAttempt_whenAnEarlierOneFailed() {
        flakyTransactionalService.failNextAttempts(1);

        flakyTransactionalService.recordAttempt("probe");

        assertThat(mongoTemplate.findAll(Document.class, COLLECTION))
            .singleElement()
            .satisfies(stored -> assertThat(stored.getInteger("attempt")).isEqualTo(2));
    }

    @Test
    void transactionalMethod_shouldPropagate_whenEveryAttemptFailsTransiently() {
        flakyTransactionalService.failNextAttempts(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flakyTransactionalService.recordAttempt("probe"))
            .isInstanceOf(TransactionSystemException.class);

        // max-attempts is 3 in application-integration-test.yml.
        assertThat(flakyTransactionalService.attempts()).isEqualTo(3);
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION)).isEmpty();
    }

    @Test
    void transactionalMethod_shouldSucceed_whenTheCommitItselfFailsTransiently() {
        transactionManager.failNextCommits(1);

        flakyTransactionalService.recordAttempt("probe");

        assertThat(flakyTransactionalService.attempts()).isEqualTo(2);
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION))
            .singleElement()
            .satisfies(stored -> assertThat(stored.getInteger("attempt")).isEqualTo(2));
    }

    @TestConfiguration
    static class FlakyServiceConfiguration {

        @Bean
        FlakyTransactionalService flakyTransactionalService(MongoTemplate mongoTemplate) {
            return new FlakyTransactionalService(mongoTemplate);
        }

        @Bean
        @Primary
        CommitFailingTransactionManager commitFailingTransactionManager(
            MongoDatabaseFactory databaseFactory) {
            return new CommitFailingTransactionManager(databaseFactory);
        }
    }

    /**
     * Fails the commit itself, the way a WriteConflict raised at commit time reaches Spring. The
     * base class turns anything thrown here into the {@link TransactionSystemException} the
     * application sees in production.
     */
    static class CommitFailingTransactionManager extends MongoTransactionManager {

        private final AtomicInteger commitFailuresRemaining = new AtomicInteger();

        CommitFailingTransactionManager(MongoDatabaseFactory databaseFactory) {
            super(databaseFactory);
        }

        void failNextCommits(int failures) {
            commitFailuresRemaining.set(failures);
        }

        @Override
        protected void doCommit(MongoTransactionObject transactionObject) throws Exception {
            if (commitFailuresRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                throw writeConflictOnOutboxCreation();
            }
            super.doCommit(transactionObject);
        }
    }

    static class FlakyTransactionalService {

        private final MongoTemplate mongoTemplate;
        private int attempts;
        private int failuresRemaining;

        FlakyTransactionalService(MongoTemplate mongoTemplate) {
            this.mongoTemplate = mongoTemplate;
        }

        void reset() {
            attempts = 0;
            failuresRemaining = 0;
        }

        void failNextAttempts(int failures) {
            failuresRemaining = failures;
        }

        int attempts() {
            return attempts;
        }

        @Transactional
        public void recordAttempt(String id) {
            attempts++;
            mongoTemplate.save(new Document("_id", id).append("attempt", attempts), COLLECTION);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw writeConflictAtCommit();
            }
        }
    }
}
