package uk.gov.defra.trade.imports.animals.configuration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ConnectionPoolSettings;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import uk.gov.defra.trade.imports.animals.configuration.tls.TrustStoreConfiguration;

/**
 * MongoDB configuration for CDP Java Backend Template.
 *
 * <p>Configures MongoDB connection with: - AWS IAM authentication (via connection string
 * authMechanism=MONGODB-AWS) - Custom SSL/TLS certificates from TRUSTSTORE_* environment variables
 * - Read preference: secondary (configurable) - Write concern: majority (configurable) - Connection
 * pooling - Graceful shutdown
 *
 * <p>Connection string format for AWS IAM auth:
 * mongodb://host:port/database?authMechanism=MONGODB-AWS&authSource=$external
 */
@Configuration
@EnableMongoAuditing
@EnableScheduling
@Slf4j
public class MongoConfig {

  @Bean
  ConnectionPoolSettings connectionPoolSettings(
      @Value("${spring.data.mongodb.connection-pool.min-size}") int minPoolSize,
      @Value("${spring.data.mongodb.connection-pool.max-size}") int maxPoolSize,
      @Value("${spring.data.mongodb.connection-pool.max-wait-time-ms}") int maxWaitTimeMs,
      @Value("${spring.data.mongodb.connection-pool.max-connection-idle-time-ms}")
          int maxIdleTimeMs) {

    return ConnectionPoolSettings.builder()
        .minSize(minPoolSize)
        .maxSize(maxPoolSize)
        .maxWaitTime(maxWaitTimeMs, TimeUnit.MILLISECONDS)
        .maxConnectionIdleTime(maxIdleTimeMs, TimeUnit.MILLISECONDS)
        .build();
  }

  @Bean
  MongoClientSettings mongoClientSettings(
      @Value("${spring.data.mongodb.ssl.enabled}") boolean sslEnabled,
      @Value("${spring.data.mongodb.uri}") String mongoUri,
      @Value("${spring.data.mongodb.read-preference}") ReadPreference readPreference,
      @Value("${spring.data.mongodb.write-concern}") WriteConcern writeConcern,
      TrustStoreConfiguration trustStoreConfiguration,
      ConnectionPoolSettings connectionPoolSettings) {

      MongoClientSettings.Builder builder = MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(mongoUri))
          .applyToConnectionPoolSettings(bdr -> bdr.applySettings(connectionPoolSettings))
          .readPreference(readPreference)
          .writeConcern(writeConcern);
      
      if (sslEnabled) {
          SSLContext sslContext = trustStoreConfiguration.customSslContext();
          builder.applyToSslSettings(bdr -> bdr.context(sslContext));
          log.info("MongoDB SSL configured with SSL Bundle");
      }
      log.info("MongoDB client configuration complete");
      
    return builder.build();
  }

  @Bean
  MongoClient mongoClient(MongoClientSettings mongoClientSettings) {
      log.info("Creating MongoDB client");
    return MongoClients.create(mongoClientSettings);
  }

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
    return new MongoTransactionManager(dbFactory);
  }

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
   */
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
        return transactionAttributeSource.getTransactionAttribute(method, targetClass) != null;
      }
    };
    DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut,
        new TransientTransactionRetryInterceptor(
            maxAttempts, initialBackoffMs, maxBackoffMs, jitterMs));
    advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    log.info("Transient Mongo transaction retry enabled: maxAttempts={}", maxAttempts);
    return advisor;
  }

  @Bean
  LockProvider lockProvider(MongoClient mongoClient,
      @Value("${spring.data.mongodb.database}") String dbName) {
    return new MongoLockProvider(mongoClient.getDatabase(dbName));
  }

  @Bean
  LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }
}
