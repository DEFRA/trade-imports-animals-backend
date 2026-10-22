package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.unknownCommitResultOnServerShutdown;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictOnOutboxCreation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.mongodb.MongoException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.MongoDatabaseFactory;

class CommitRetryingMongoTransactionManagerTest {

    private static final long RETRY_WINDOW_MS = 1000;
    private static final long BACKOFF_MS = 10;

    @Test
    void doCommit_shouldCommitOnce_whenTheCommitSucceeds() throws Exception {
        CommitProbe probe = new CommitProbe(RETRY_WINDOW_MS);

        probe.commit();

        assertThat(probe.commits()).isEqualTo(1);
    }

    @Test
    void doCommit_shouldReCommitAndSucceed_whenTheFirstCommitResultIsUnknown() throws Exception {
        CommitProbe probe = new CommitProbe(RETRY_WINDOW_MS);
        probe.failNextCommits(1, unknownCommitResultOnServerShutdown());

        probe.commit();

        assertThat(probe.commits()).isEqualTo(2);
    }

    @Test
    void doCommit_shouldPropagate_whenTheCommitFailsWithoutTheUnknownResultLabel() {
        MongoException failure = writeConflictOnOutboxCreation();
        CommitProbe probe = new CommitProbe(RETRY_WINDOW_MS);
        probe.failNextCommits(Integer.MAX_VALUE, failure);

        assertThatThrownBy(probe::commit).isSameAs(failure);

        assertThat(probe.commits()).isEqualTo(1);
    }

    @Test
    void doCommit_shouldRethrowTheFailure_whenTheRetryDeadlinePasses() {
        withCommitRetryLoggingMuted(() -> {
            MongoException failure = unknownCommitResultOnServerShutdown();
            CommitProbe probe = new CommitProbe(RETRY_WINDOW_MS);
            probe.failNextCommits(Integer.MAX_VALUE, failure);

            assertThatThrownBy(probe::commit).isSameAs(failure);

            assertThat(probe.commits()).isGreaterThan(1);
        });
    }

    private static void withCommitRetryLoggingMuted(Runnable test) {
        Logger logger =
            (Logger) LoggerFactory.getLogger(CommitRetryingMongoTransactionManager.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            test.run();
        } finally {
            logger.setLevel(previous);
        }
    }

    private static final class CommitProbe extends CommitRetryingMongoTransactionManager {

        private final MongoTransactionObject transactionObject = mock(MongoTransactionObject.class);
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private MongoException failure;

        private CommitProbe(long timeoutMs) {
            super(mock(MongoDatabaseFactory.class), timeoutMs, BACKOFF_MS);
            doAnswer(invocation -> {
                commits.incrementAndGet();
                if (failuresRemaining.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    throw failure;
                }
                return null;
            }).when(transactionObject).commitTransaction();
        }

        private void failNextCommits(int failures, MongoException commitFailure) {
            this.failure = commitFailure;
            failuresRemaining.set(failures);
        }

        private void commit() throws Exception {
            doCommit(transactionObject);
        }

        private int commits() {
            return commits.get();
        }
    }
}
