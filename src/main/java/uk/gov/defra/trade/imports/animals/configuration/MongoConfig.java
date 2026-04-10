package uk.gov.defra.trade.imports.animals.configuration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ConnectionPoolSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import javax.net.ssl.SSLContext;
import java.util.concurrent.TimeUnit;
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
}
