package uk.gov.defra.trade.imports.animals.configuration;

import com.mongodb.MongoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;

/**
 * Re-issues the commit on the same session when MongoDB labels it
 * {@code UnknownTransactionCommitResult}.
 */
@Slf4j
public class CommitRetryingMongoTransactionManager extends MongoTransactionManager {

    private final transient RetryTemplate commitRetry;

    public CommitRetryingMongoTransactionManager(MongoDatabaseFactory databaseFactory,
        long retryTimeoutMs, long retryBackoffMs) {

        super(databaseFactory);
        this.commitRetry = RetryTemplate.builder()
            .withTimeout(retryTimeoutMs)
            .fixedBackoff(retryBackoffMs)
            .retryOn(CommitRetryingMongoTransactionManager::isUnknownCommitResult)
            .withListener(new CommitRetryLogger(retryTimeoutMs))
            .build();
    }

    @Override
    protected void doCommit(MongoTransactionObject transactionObject) throws Exception {
        commitRetry.execute(context -> {
            super.doCommit(transactionObject);
            return null;
        });
    }

    private static boolean isUnknownCommitResult(Throwable failure) {
        return failure instanceof MongoException mongoException
            && mongoException.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
    }

    private record CommitRetryLogger(long retryTimeoutMs) implements RetryListener {

        @Override
        public <T, E extends Throwable> void onError(RetryContext context,
            RetryCallback<T, E> callback, Throwable failure) {

            if (isUnknownCommitResult(failure)) {
                log.warn("Mongo commit result unknown: attempt={}", context.getRetryCount());
            }
        }

        @Override
        public <T, E extends Throwable> void close(RetryContext context,
            RetryCallback<T, E> callback, Throwable failure) {

            if (isUnknownCommitResult(failure)) {
                log.error("Mongo commit result still unknown after {} attempts and {}ms",
                    context.getRetryCount(), retryTimeoutMs, failure);
            }
        }
    }
}
