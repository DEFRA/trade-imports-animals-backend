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
 * Retries a transaction that MongoDB has told us is safe to re-run from the beginning.
 *
 * <p>MongoDB labels a failure it considers safe to repeat — {@code WriteConflict} among them —
 * with {@code TransientTransactionError}. Spring's declarative {@code @Transactional} path does
 * not act on that label, so the failure surfaces to the caller as a 500.
 *
 * <p>The label is the only reliable signal. The failure arrives as a
 * {@code TransactionSystemException} thrown at commit, which is outside Spring's data-access
 * exception hierarchy altogether; and even on the operation path, {@code MongoDbErrorCodes}
 * classifies {@code WriteConflict} as a data-integrity violation, which is not transient. A retry
 * predicate written against Spring's exception types would therefore never fire.
 *
 * <p>Only {@code TransientTransactionError} is matched, and deliberately not
 * {@code UnknownTransactionCommitResult}. MongoDB gives the two labels different contracts: the
 * first says the transaction definitely did not commit, so re-running the whole body is correct;
 * the second says the commit may well have <em>succeeded</em> and the correct response is to retry
 * {@code commitTransaction} on the same session. Re-running the body after a commit that landed
 * would re-read state the first attempt already changed, and turn a success into a spurious 4xx —
 * a stale {@code @Version} token, or a status guard that now sees the status it just set. Spring
 * Data's {@code MongoExceptionTranslator#isTransientFailure} conflates the two, so this walks the
 * cause chain itself.
 *
 * <p>Ordered outside the transaction advisor (see {@code TransactionRetryConfiguration}) so a retry
 * begins a genuinely new transaction rather than re-running work inside the doomed one. Only the
 * outermost transaction retries: an inner {@code @Transactional} call joins its caller's
 * transaction, so repeating it in place would achieve nothing.
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

    /**
     * Whether MongoDB has told us the transaction definitely did not commit, and so can be re-run
     * from the start. Walks the cause chain because the driver's exception arrives wrapped, and
     * matches the one label that carries that meaning.
     */
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

    /**
     * Waits before the next attempt, backing off exponentially with jitter so racing callers do
     * not line up and conflict again.
     *
     * <p>An interrupt abandons the retry and rethrows the failure that prompted it, with the
     * thread's interrupt flag restored.
     */
    private void backOff(int attempt, RuntimeException failure) {
        long doublings = Math.min(attempt - 1L, MAX_BACKOFF_DOUBLINGS);
        // The shift can overflow to a negative for an absurdly large configured backoff, and
        // Thread.sleep rejects a negative, so clamp before waiting.
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
     * The MongoDB error code, its name and the namespace involved — enough to tell a collision
     * over creating a collection from one over a document.
     *
     * <p>Deliberately not the driver exception's own {@code toString()}: for a
     * {@link MongoCommandException} that is the entire server response, {@code $clusterTime} and
     * its signature included, which runs to kilobytes on every retry.
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
