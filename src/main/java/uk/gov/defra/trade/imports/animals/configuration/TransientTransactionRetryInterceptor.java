package uk.gov.defra.trade.imports.animals.configuration;

import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retries a transaction that MongoDB has told us to retry.
 *
 * <p>MongoDB labels a failure it considers safe to repeat — {@code WriteConflict} among them —
 * with {@code TransientTransactionError}. Spring's declarative {@code @Transactional} path does
 * not act on that label, so the failure surfaces to the caller as a 500.
 *
 * <p>The label is the only reliable signal. The failure arrives as a
 * {@code TransactionSystemException} thrown at commit, which is outside Spring's data-access
 * exception hierarchy altogether; and even on the operation path, {@code MongoDbErrorCodes}
 * classifies {@code WriteConflict} as a data-integrity violation, which is not transient. A retry
 * predicate written against Spring's exception types would therefore never fire. This delegates to
 * {@link MongoExceptionTranslator#isTransientFailure}, which walks the cause chain and tests the
 * driver's own error labels.
 *
 * <p>Ordered outside the transaction advisor (see {@code MongoConfig}) so a retry begins a
 * genuinely new transaction rather than re-running work inside the doomed one. Only the outermost
 * transaction retries: an inner {@code @Transactional} call joins its caller's transaction, so
 * repeating it in place would achieve nothing.
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

        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            try {
                return attempt(proxyInvocation);
            } catch (RuntimeException e) {
                if (!isTransient(e)) {
                    throw e;
                }
                log.warn("Retrying transient Mongo transaction: method={} attempt={}/{} cause={}",
                    describe(invocation), attempt, maxAttempts, rootCause(e));
                backOff(attempt, e);
            }
        }

        try {
            return attempt(proxyInvocation);
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                log.error("Transient Mongo transaction still failing after {} attempts: method={}",
                    maxAttempts, describe(invocation), e);
            }
            throw e;
        }
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

    private static boolean isTransient(RuntimeException e) {
        return MongoExceptionTranslator.DEFAULT_EXCEPTION_TRANSLATOR.isTransientFailure(e);
    }

    /**
     * Waits before the next attempt, backing off exponentially with jitter so racing callers do
     * not line up and conflict again.
     *
     * <p>An interrupt abandons the retry and rethrows the failure that prompted it, with the
     * thread's interrupt flag restored.
     */
    private void backOff(int attempt, RuntimeException failure) {
        long doublings = Math.min(attempt - 1L, MAX_BACKOFF_DOUBLINGS);
        long delay = Math.min(maxBackoffMs, initialBackoffMs << doublings);
        long jitter = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs) : 0;
        try {
            Thread.sleep(delay + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /**
     * The driver's own exception, which names the MongoDB error code and the namespace involved.
     * The wrapper says only that the commit failed, which does not distinguish a collision over
     * creating a collection from one over a document.
     */
    private static String rootCause(RuntimeException e) {
        return String.valueOf(NestedExceptionUtils.getMostSpecificCause(e));
    }

    private static String describe(MethodInvocation invocation) {
        return invocation.getMethod().getDeclaringClass().getSimpleName()
            + "." + invocation.getMethod().getName();
    }
}
