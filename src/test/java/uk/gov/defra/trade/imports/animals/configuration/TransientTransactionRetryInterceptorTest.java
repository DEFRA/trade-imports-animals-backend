package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictAtCommit;

import com.mongodb.MongoCommandException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;

/**
 * Drives the interceptor through a real {@link ProxyFactory} proxy rather than a stubbed
 * invocation, so each retry exercises the same {@code MethodInvocation} plumbing it meets in the
 * application context.
 */
class TransientTransactionRetryInterceptorTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final String RESULT = "saved";

    @AfterEach
    void clearThreadState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        Thread.interrupted();
    }

    @Test
    void invoke_shouldReturnResult_whenTheInvocationSucceeds() {
        CountingWork work = new CountingWork(0);

        assertThat(proxied(work).run()).isEqualTo(RESULT);
        assertThat(work.invocations).isEqualTo(1);
    }

    @Test
    void invoke_shouldRetryAndSucceed_whenTheFirstAttemptFailsWithWriteConflict() {
        CountingWork work = new CountingWork(1);

        assertThat(proxied(work).run()).isEqualTo(RESULT);
        assertThat(work.invocations).isEqualTo(2);
    }

    @Test
    void invoke_shouldRethrowTheLastFailure_whenEveryAttemptIsTransient() {
        CountingWork work = new CountingWork(Integer.MAX_VALUE);
        Work proxy = proxied(work);

        assertThatThrownBy(proxy::run)
            .isInstanceOf(TransactionSystemException.class)
            .hasMessageContaining("Could not commit Mongo transaction")
            .hasRootCauseInstanceOf(MongoCommandException.class);
        assertThat(work.invocations).isEqualTo(MAX_ATTEMPTS);
    }

    @ParameterizedTest
    @MethodSource("nonTransientFailures")
    void invoke_shouldNotRetry_whenTheFailureIsNotTransient(RuntimeException failure) {
        CountingWork work = new CountingWork(Integer.MAX_VALUE, failure);
        Work proxy = proxied(work);

        assertThatThrownBy(proxy::run).isSameAs(failure);
        assertThat(work.invocations).isEqualTo(1);
    }

    static List<RuntimeException> nonTransientFailures() {
        return List.of(
            new BadRequestException("Country code is required"),
            new OptimisticLockingFailureException("Stale concurrency token"),
            new IllegalStateException("Programming error"));
    }

    @Test
    void invoke_shouldNotRetry_whenAlreadyInsideATransaction() {
        CountingWork work = new CountingWork(Integer.MAX_VALUE);
        Work proxy = proxied(work);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(proxy::run).isInstanceOf(TransactionSystemException.class);
        assertThat(work.invocations).isEqualTo(1);
    }

    @Test
    void invoke_shouldRestoreTheInterruptFlag_whenTheBackoffIsInterrupted() {
        CountingWork work = new CountingWork(Integer.MAX_VALUE);
        Work proxy = proxied(work);
        Thread.currentThread().interrupt();

        assertThatThrownBy(proxy::run).isInstanceOf(TransactionSystemException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(work.invocations).isEqualTo(1);
    }

    private static Work proxied(Work target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransientTransactionRetryInterceptor(MAX_ATTEMPTS, 1, 2, 0));
        return (Work) factory.getProxy();
    }

    interface Work {
        String run();
    }

    private static final class CountingWork implements Work {

        private final int failuresBeforeSuccess;
        private final RuntimeException failure;
        private int invocations;

        private CountingWork(int failuresBeforeSuccess) {
            this(failuresBeforeSuccess, null);
        }

        private CountingWork(int failuresBeforeSuccess, RuntimeException failure) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.failure = failure;
        }

        @Override
        public String run() {
            invocations++;
            if (invocations <= failuresBeforeSuccess) {
                throw failure != null ? failure : writeConflictAtCommit();
            }
            return RESULT;
        }
    }
}
