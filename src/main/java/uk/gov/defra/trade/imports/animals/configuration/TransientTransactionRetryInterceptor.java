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

/** Retries a transaction MongoDB has labelled {@code TransientTransactionError}. */
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

        private static String summarise(Throwable failure) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
            if (cause instanceof MongoCommandException command) {
                return "code=%d codeName=%s errmsg=%s".formatted(command.getErrorCode(),
                    command.getErrorCodeName(), command.getErrorMessage());
            }
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }
}
