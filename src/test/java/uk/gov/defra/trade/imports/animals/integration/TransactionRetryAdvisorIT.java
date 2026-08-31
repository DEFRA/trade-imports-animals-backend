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
 *
 * <p>The unit test cannot show either. Both depend on wiring that fails silently: the advisor is
 * only picked up if the auto-proxy creator considers it, and a retry only helps if its order puts
 * it outside the transaction advisor. If that regresses, the unit tests stay green and the 500s
 * come back — which is what this test exists to catch.
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

        flakyTransactionalService.record("probe");

        assertThat(flakyTransactionalService.attempts()).isEqualTo(2);
    }

    @Test
    void transactionalMethod_shouldCommitOnlyTheSuccessfulAttempt_whenAnEarlierOneFailed() {
        flakyTransactionalService.failNextAttempts(1);

        flakyTransactionalService.record("probe");

        // One document, written by the second attempt: the first attempt's write rolled back, so
        // the retry ran in a genuinely new transaction rather than inside the failed one.
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION))
            .singleElement()
            .satisfies(stored -> assertThat(stored.getInteger("attempt")).isEqualTo(2));
    }

    @Test
    void transactionalMethod_shouldPropagate_whenEveryAttemptFailsTransiently() {
        flakyTransactionalService.failNextAttempts(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flakyTransactionalService.record("probe"))
            .isInstanceOf(TransactionSystemException.class);

        // max-attempts is 3 in application-integration-test.yml.
        assertThat(flakyTransactionalService.attempts()).isEqualTo(3);
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION)).isEmpty();
    }

    /**
     * The production shape of the reported failure: the method body completes, and MongoDB
     * rejects the <em>commit</em>. That is a different path from a body that throws — the
     * transaction manager raises it out of {@code doCommit} and wraps it itself — and it is the
     * one the WriteConflicts in the E2E logs actually took.
     */
    @Test
    void transactionalMethod_shouldSucceed_whenTheCommitItselfFailsTransiently() {
        transactionManager.failNextCommits(1);

        flakyTransactionalService.record("probe");

        assertThat(flakyTransactionalService.attempts()).isEqualTo(2);
        // The failed commit left nothing behind, so only the second attempt's write is stored.
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

        /**
         * Takes precedence over the application's own transaction manager for this context only —
         * the {@code @Import} above already gives this test class a context of its own.
         */
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

    /**
     * Writes inside a transaction, then fails from the method body. Complements the commit-time
     * case above: the retry has to cope with both, and the interceptor sits outside the
     * transaction advisor so it sees them identically.
     */
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
        public void record(String id) {
            attempts++;
            mongoTemplate.save(new Document("_id", id).append("attempt", attempts), COLLECTION);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw writeConflictAtCommit();
            }
        }
    }
}
