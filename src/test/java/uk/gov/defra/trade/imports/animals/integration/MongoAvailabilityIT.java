package uk.gov.defra.trade.imports.animals.integration;

import static org.awaitility.Awaitility.await;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.defra.trade.imports.animals.metrics.MongoAvailability;

class MongoAvailabilityIT extends IntegrationBase {

  @Autowired private MongoAvailability applicationMongoAvailability;

  @Test
  void state_shouldBeAvailable_whenTheApplicationsDatabaseIsUp() {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> applicationMongoAvailability.state() == MongoAvailability.State.AVAILABLE);
  }

  @Test
  void state_shouldBeUnavailable_whenTheDatabaseStops() {
    MongoAvailability availability = new MongoAvailability();
    try (MongoDBContainer throwaway = new MongoDBContainer(DockerImageName.parse("mongo:7.0"))) {
      throwaway.start();
      try (var _ = clientOf(throwaway, availability)) {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> availability.state() == MongoAvailability.State.AVAILABLE);

        throwaway.stop();

        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> availability.state() == MongoAvailability.State.UNAVAILABLE);
      }
    }
  }

  private static MongoClient clientOf(MongoDBContainer database, MongoAvailability availability) {
    return MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(database.getConnectionString()))
            .applyToClusterSettings(cluster -> cluster.addClusterListener(availability))
            .applyToServerSettings(
                server -> server.heartbeatFrequency(500, TimeUnit.MILLISECONDS))
            .build());
  }
}
