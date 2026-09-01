package uk.gov.defra.trade.imports.animals.configuration;

import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Wraps every {@code @Transactional} method in a bounded retry of transient MongoDB failures.
 *
 * <p>The pointcut consults the {@link TransactionAttributeSource} Spring's own advisor uses, so it
 * matches exactly the methods that get a transaction.
 *
 * <p>The order puts this outside the transaction advisor ({@link Ordered#LOWEST_PRECEDENCE}), so a
 * retry starts a fresh transaction rather than re-running inside the failed one.
 *
 * <p>Declared as {@link BeanDefinition#ROLE_INFRASTRUCTURE} exactly as Spring declares
 * {@code ProxyTransactionManagementConfiguration}: the auto-proxy creator fetches advisors before
 * the bean post-processors are ready, and without this every bean declared beside it would be
 * quietly denied post-processing.
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@Slf4j
public class TransactionRetryConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    DefaultPointcutAdvisor transactionRetryAdvisor(
        TransactionAttributeSource transactionAttributeSource,
        @Value("${mongo.transaction.retry.max-attempts}") int maxAttempts,
        @Value("${mongo.transaction.retry.initial-backoff-ms}") long initialBackoffMs,
        @Value("${mongo.transaction.retry.max-backoff-ms}") long maxBackoffMs,
        @Value("${mongo.transaction.retry.jitter-ms}") long jitterMs) {

        Pointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return transactionAttributeSource.getTransactionAttribute(method, targetClass)
                    != null;
            }
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut,
            new TransientTransactionRetryInterceptor(
                maxAttempts, initialBackoffMs, maxBackoffMs, jitterMs));
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        log.info("Transient Mongo transaction retry enabled: maxAttempts={}", maxAttempts);
        return advisor;
    }
}
