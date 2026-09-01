package uk.gov.defra.trade.imports.animals.configuration;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retries a transaction MongoDB has labelled {@code TransientTransactionError}, which Spring's
 * declarative {@code @Transactional} path ignores — so the failure reaches the caller as a 500.
 *
 * <p>The label is the only reliable signal: the failure arrives as a
 * {@code TransactionSystemException} at commit, outside Spring's data-access hierarchy, and
 * {@code WriteConflict} otherwise classifies as a non-transient data-integrity violation.
 *
 * <p>{@code UnknownTransactionCommitResult} is deliberately not matched: it means the commit may
 * have succeeded, so re-running the body would repeat work against changed state. Spring Data's
 * {@code isTransientFailure} conflates the two, hence the cause walk here.
 *
 * <p>Ordered outside the transaction advisor so a retry begins a new transaction. Only the
 * outermost retries — an inner call joins its caller's transaction.
 */
@Slf4j
public class TransientTransactionRetryInterceptor implements MethodInterceptor {

    /** Caps the exponential shift so a mis-set attempt count cannot overflow the backoff. */
    private static final int MAX_BACKOFF_DOUBLINGS = 16;

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long jitterMs;

    public TransientTransactionRetryInterceptor(int maxAttempts, long initialBackoffMs,
        long maxBackoffMs, long jitterMs) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                "mongo.transaction.retry.max-attempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
        this.jitterMs = Math.max(0, jitterMs);
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || !(invocation instanceof ProxyMethodInvocation proxyInvocation)) {
            return invocation.proceed();
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return attempt(proxyInvocation);
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt == maxAttempts) {
                    log.error("Transient Mongo transaction still failing after {} attempts: "
                        + "method={}", maxAttempts, describe(invocation), e);
                    throw e;
                }
                log.warn("Retrying transient Mongo transaction: method={} attempt={}/{} cause={}",
                    describe(invocation), attempt, maxAttempts, summarise(e));
                backOff(attempt, e);
            }
        }
        // Unreachable: the final attempt either returns or rethrows above, and maxAttempts is at
        // least 1, so the loop always runs at least once.
        throw new IllegalStateException("Retry loop exited without a result or a failure");
    }

    /**
     * Runs one attempt down the rest of the interceptor chain.
     *
     * <p>A {@link MethodInvocation} carries its position in the chain as mutable state, so calling
     * {@code proceed()} on the same instance twice would skip every interceptor below us — the
     * transaction interceptor included. Each attempt therefore runs on a fresh clone.
     */
    private static Object attempt(ProxyMethodInvocation invocation) throws Throwable {
        return invocation.invocableClone().proceed();
    }

    /** Walks the cause chain because the driver's exception arrives wrapped. */
    private static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = nextCause(cause)) {
            if (cause instanceof MongoException mongoException
                && mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable nextCause(Throwable cause) {
        return cause.getCause() != cause ? cause.getCause() : null;
    }

    /** Jittered so racing callers do not line up and conflict again. */
    private void backOff(int attempt, RuntimeException failure) {
        long doublings = Math.min(attempt - 1L, MAX_BACKOFF_DOUBLINGS);
        // Clamp: the shift can overflow negative, and Thread.sleep rejects that.
        long shifted = Math.max(0, initialBackoffMs << doublings);
        long delay = Math.min(maxBackoffMs, shifted);
        long jitter = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs) : 0;
        try {
            Thread.sleep(delay + jitter);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /**
     * Not the exception's {@code toString()}: for a {@link MongoCommandException} that is the
     * whole server response, {@code $clusterTime} included, and runs to kilobytes per retry.
     */
    private static String summarise(RuntimeException e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        if (cause instanceof MongoCommandException command) {
            return "code=%d codeName=%s errmsg=%s".formatted(command.getErrorCode(),
                command.getErrorCodeName(), command.getErrorMessage());
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String describe(MethodInvocation invocation) {
        return invocation.getMethod().getDeclaringClass().getSimpleName()
            + "." + invocation.getMethod().getName();
    }
}
