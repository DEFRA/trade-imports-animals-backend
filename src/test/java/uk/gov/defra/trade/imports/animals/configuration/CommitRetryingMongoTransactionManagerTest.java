package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.unknownCommitResultOnServerShutdown;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictOnOutboxCreation;

import com.mongodb.MongoException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;

class CommitRetryingMongoTransactionManagerTest {

    private static final long TIMEOUT_MS = 50;
    private static final long BACKOFF_MS = 10;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void doCommit_shouldCommitOnce_whenTheCommitSucceeds() throws Exception {
        CommitProbe probe = new CommitProbe(TIMEOUT_MS);

        probe.commit();

        assertThat(probe.commits()).isEqualTo(1);
    }

    @Test
    void doCommit_shouldReCommitAndSucceed_whenTheFirstCommitResultIsUnknown() throws Exception {
        CommitProbe probe = new CommitProbe(TIMEOUT_MS);
        probe.failNextCommits(1, unknownCommitResultOnServerShutdown());

        probe.commit();

        assertThat(probe.commits()).isEqualTo(2);
    }

    @Test
    void doCommit_shouldPropagate_whenTheCommitFailsWithoutTheUnknownResultLabel() {
        MongoException failure = writeConflictOnOutboxCreation();
        CommitProbe probe = new CommitProbe(TIMEOUT_MS);
        probe.failNextCommits(Integer.MAX_VALUE, failure);

        assertThatThrownBy(probe::commit).isSameAs(failure);

        assertThat(probe.commits()).isEqualTo(1);
    }

    @Test
    void doCommit_shouldRethrowTheFailure_whenTheRetryDeadlinePasses() {
        MongoException failure = unknownCommitResultOnServerShutdown();
        CommitProbe probe = new CommitProbe(TIMEOUT_MS);
        probe.failNextCommits(Integer.MAX_VALUE, failure);

        assertThatThrownBy(probe::commit).isSameAs(failure);

        assertThat(probe.commits()).isGreaterThan(1);
    }

    @Test
    void doCommit_shouldRethrowTheFailure_whenThereIsNoTimeLeftToRetry() {
        MongoException failure = unknownCommitResultOnServerShutdown();
        CommitProbe probe = new CommitProbe(0);
        probe.failNextCommits(1, failure);

        assertThatThrownBy(probe::commit).isSameAs(failure);

        assertThat(probe.commits()).isEqualTo(1);
    }

    @Test
    void doCommit_shouldAbortTheRetryAndRestoreTheInterrupt_whenTheThreadIsInterrupted() {
        MongoException failure = unknownCommitResultOnServerShutdown();
        CommitProbe probe = new CommitProbe(TIMEOUT_MS);
        probe.failNextCommits(Integer.MAX_VALUE, failure);
        Thread.currentThread().interrupt();

        assertThatThrownBy(probe::commit).isSameAs(failure);

        assertThat(Thread.interrupted()).isTrue();
        assertThat(probe.commits()).isEqualTo(1);
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
