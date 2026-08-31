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
 * <p>The pointcut consults the very {@link TransactionAttributeSource} Spring's own transaction
 * advisor uses, so the retry applies to exactly the set of methods that get a transaction —
 * neither more nor less — however the annotation is expressed.
 *
 * <p>The order puts this advisor <em>outside</em> the transaction advisor, which defaults to
 * {@link Ordered#LOWEST_PRECEDENCE}. Lower values sit further out, so a retry re-enters the
 * transaction interceptor and starts a fresh transaction. Anything at or inside that boundary
 * would re-run the work in the transaction that has already failed.
 *
 * <p>This lives in its own {@code @Configuration} rather than alongside the Mongo connection
 * beans, and is declared exactly as Spring declares
 * {@code ProxyTransactionManagementConfiguration}: {@code proxyBeanMethods = false} and
 * {@link BeanDefinition#ROLE_INFRASTRUCTURE} on the class as well as the bean. The auto-proxy
 * creator fetches every {@link org.springframework.aop.Advisor} while the bean post-processors are
 * still being set up, so the declaring class is forced into existence before they are ready.
 * Declaring it as infrastructure says that is intended; leaving an advisor on an ordinary
 * configuration class instead draws a "not eligible for getting processed by all
 * BeanPostProcessors" warning at startup, and would quietly deny post-processing to every other
 * bean declared beside it.
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
