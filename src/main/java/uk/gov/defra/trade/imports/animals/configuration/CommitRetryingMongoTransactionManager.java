package uk.gov.defra.trade.imports.animals.configuration;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.mongodb.MongoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Re-issues the <em>commit</em> on the same session when MongoDB labels it
 * {@code UnknownTransactionCommitResult} — the commit may already have landed, so re-running the
 * transaction body instead would apply the work twice. That is the opposite of
 * {@link TransientTransactionRetryInterceptor}, which re-runs the body and deliberately ignores
 * this label; between them they reproduce what the driver's own {@code withTransaction} does.
 *
 * <p>Bounded by a deadline rather than an attempt count, as the driver is. The driver also raises
 * the write concern to {@code majority} before each commit retry, which this hook cannot reach —
 * {@code spring.data.mongodb.write-concern} already applies {@code majority} to every transaction.
 */
@Slf4j
public class CommitRetryingMongoTransactionManager extends MongoTransactionManager {

    private final long retryTimeoutMs;
    private final long retryBackoffMs;

    public CommitRetryingMongoTransactionManager(MongoDatabaseFactory databaseFactory,
        long retryTimeoutMs, long retryBackoffMs) {

        super(databaseFactory);
        this.retryTimeoutMs = retryTimeoutMs;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    protected void doCommit(MongoTransactionObject transactionObject) throws Exception {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(retryTimeoutMs);
        int attempts = 0;

        while (true) {
            attempts++;
            try {
                super.doCommit(transactionObject);
                return;
            } catch (MongoException failure) {
                if (!failure.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    throw failure;
                }
                if (System.nanoTime() >= deadline) {
                    log.error("Mongo commit result still unknown after {} attempts and {}ms",
                        attempts, retryTimeoutMs, failure);
                    throw failure;
                }
                if (!backOff()) {
                    log.error("Interrupted before re-committing a Mongo transaction whose result "
                        + "was unknown after {} attempts", attempts, failure);
                    throw failure;
                }
                log.warn("Mongo commit result unknown, re-committing the same session: attempt={}",
                    attempts);
            }
        }
    }

    private boolean backOff() {
        try {
            Thread.sleep(retryBackoffMs);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
