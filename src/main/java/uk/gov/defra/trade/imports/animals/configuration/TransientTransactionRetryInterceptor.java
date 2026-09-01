package uk.gov.defra.trade.imports.animals.configuration;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.support.RetryTemplate;
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
 */
@Slf4j
public class TransientTransactionRetryInterceptor extends RetryOperationsInterceptor {

    private static final double BACKOFF_MULTIPLIER = 2.0;

    public TransientTransactionRetryInterceptor(int maxAttempts, long initialBackoffMs,
        long maxBackoffMs) {

        setRetryOperations(RetryTemplate.builder()
            .maxAttempts(maxAttempts)
            .exponentialBackoff(initialBackoffMs, BACKOFF_MULTIPLIER, maxBackoffMs, true)
            .retryOn(TransientTransactionRetryInterceptor::isTransientTransactionFailure)
            .withListener(new RetryLogger(maxAttempts))
            .build());
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // An inner call has already joined its caller's transaction, so retrying here would re-run
        // inside the transaction that is failing rather than in a fresh one.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return invocation.proceed();
        }
        return super.invoke(invocation);
    }

    private static boolean isTransientTransactionFailure(Throwable failure) {
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
     * Not the exception's {@code toString()}: for a {@link MongoCommandException} that is the
     * whole server response, {@code $clusterTime} included, and runs to kilobytes per retry.
     */
    private static String summarise(Throwable failure) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        if (cause instanceof MongoCommandException command) {
            return "code=%d codeName=%s errmsg=%s".formatted(command.getErrorCode(),
                command.getErrorCodeName(), command.getErrorMessage());
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private record RetryLogger(int maxAttempts) implements RetryListener {

        @Override
        public <T, E extends Throwable> void onError(RetryContext context,
            RetryCallback<T, E> callback, Throwable failure) {

            if (context.getRetryCount() < maxAttempts && isTransientTransactionFailure(failure)) {
                log.warn("Retrying transient Mongo transaction: method={} attempt={}/{} cause={}",
                    callback.getLabel(), context.getRetryCount(), maxAttempts, summarise(failure));
            }
        }

        @Override
        public <T, E extends Throwable> void close(RetryContext context,
            RetryCallback<T, E> callback, Throwable failure) {

            if (failure != null && isTransientTransactionFailure(failure)) {
                log.error("Transient Mongo transaction still failing after {} attempts: method={}",
                    context.getRetryCount(), callback.getLabel(), failure);
            }
        }
    }
}
