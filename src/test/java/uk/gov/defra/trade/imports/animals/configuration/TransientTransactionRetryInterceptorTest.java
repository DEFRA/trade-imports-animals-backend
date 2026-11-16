package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.logEventsDuring;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.messagesAt;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.transientNetworkFailureAtCommit;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.unknownCommitResultAtCommit;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictAtCommit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;

class TransientTransactionRetryInterceptorTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final String RESULT = "saved";

    @AfterEach
    void clearThreadState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static List<ILoggingEvent> interceptorLogEventsDuring(Runnable action) {
        return logEventsDuring(TransientTransactionRetryInterceptor.class, action);
    }

    @Test
    void invoke_shouldReturnResult_whenTheInvocationSucceeds() {
        CountingWork work = new CountingWork(0, writeConflictAtCommit());

        assertThat(proxied(work).run()).isEqualTo(RESULT);
        assertThat(work.invocations).isEqualTo(1);
    }

    @Test
    void invoke_shouldRetryAndSucceed_whenTheFirstAttemptFailsWithWriteConflict() {
        CountingWork work = new CountingWork(1, writeConflictAtCommit());

        assertThat(proxied(work).run()).isEqualTo(RESULT);
        assertThat(work.invocations).isEqualTo(2);
    }

    @Test
    void invoke_shouldRethrowTheOriginalFailure_whenEveryAttemptIsTransient() {
        RuntimeException failure = writeConflictAtCommit();
        CountingWork work = new CountingWork(Integer.MAX_VALUE, failure);

        assertThatThrownBy(proxied(work)::run).isSameAs(failure);
        assertThat(work.invocations).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void invoke_shouldLogAnErrorNamingTheAttemptCount_whenTheRetriesAreExhausted() {
        CountingWork work = new CountingWork(Integer.MAX_VALUE, writeConflictAtCommit());

        List<ILoggingEvent> events = interceptorLogEventsDuring(
            () -> assertThatThrownBy(proxied(work)::run).isInstanceOf(RuntimeException.class));

        assertThat(messagesAt(Level.ERROR, events))
            .singleElement()
            .satisfies(message -> assertThat(message)
                .contains("still failing after " + MAX_ATTEMPTS + " attempts"));
        assertThat(messagesAt(Level.WARN, events)).hasSize(MAX_ATTEMPTS - 1);
    }

    @Test
    void invoke_shouldNotRetry_whenTheCommitResultIsUnknown() {
        RuntimeException failure = unknownCommitResultAtCommit();
        CountingWork work = new CountingWork(Integer.MAX_VALUE, failure);

        List<ILoggingEvent> events = interceptorLogEventsDuring(
            () -> assertThatThrownBy(proxied(work)::run).isSameAs(failure));

        assertThat(work.invocations).isEqualTo(1);
        assertThat(messagesAt(Level.WARN, events)).isEmpty();
        assertThat(messagesAt(Level.ERROR, events)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("nonTransientFailures")
    void invoke_shouldNotRetry_whenTheFailureIsNotTransient(RuntimeException failure) {
        CountingWork work = new CountingWork(Integer.MAX_VALUE, failure);

        assertThatThrownBy(proxied(work)::run).isSameAs(failure);
        assertThat(work.invocations).isEqualTo(1);
    }

    static List<RuntimeException> nonTransientFailures() {
        return List.of(
            new BadRequestException("Country code is required"),
            new OptimisticLockingFailureException("Stale concurrency token"),
            new IllegalStateException("Programming error"));
    }

    @Test
    void invoke_shouldNotRetry_whenTheCallerIsAlreadyInsideATransaction() {
        RuntimeException failure = writeConflictAtCommit();
        CountingWork work = new CountingWork(Integer.MAX_VALUE, failure);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(proxied(work)::run).isSameAs(failure);
        assertThat(work.invocations).isEqualTo(1);
    }

    @Test
    void invoke_shouldSummariseTheExceptionItself_whenTheFailureCarriesNoServerResponse() {
        CountingWork work = new CountingWork(1, transientNetworkFailureAtCommit());

        List<String> results = new ArrayList<>();

        List<ILoggingEvent> events = interceptorLogEventsDuring(() -> results.add(proxied(work).run()));

        assertThat(results).containsExactly(RESULT);
        assertThat(messagesAt(Level.WARN, events))
            .singleElement()
            .satisfies(message -> assertThat(message)
                .contains("MongoException: Connection reset by peer")
                .doesNotContain("code="));
    }

    private static Work proxied(Work target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransientTransactionRetryInterceptor(MAX_ATTEMPTS, 1, 2));
        return (Work) factory.getProxy();
    }

    interface Work {
        String run();
    }

    private static final class CountingWork implements Work {

        private final int failuresBeforeSuccess;
        private final RuntimeException failure;
        private int invocations;

        private CountingWork(int failuresBeforeSuccess, RuntimeException failure) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.failure = failure;
        }

        @Override
        public String run() {
            invocations++;
            if (invocations <= failuresBeforeSuccess) {
                throw failure;
            }
            return RESULT;
        }
    }
}
