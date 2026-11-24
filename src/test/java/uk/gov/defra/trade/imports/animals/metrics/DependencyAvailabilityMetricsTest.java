package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.availability;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.failing;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.primary;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.report;

import com.mongodb.ServerAddress;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DependencyAvailabilityMetricsTest {

  private static final ServerAddress ADDRESS = new ServerAddress("mongo-1", 27017);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MongoAvailability mongoAvailability = new MongoAvailability();
  private final LastCallOutcomes lastCallOutcomes = new LastCallOutcomes();

  @BeforeEach
  void registerTheGauges() {
    new DependencyAvailabilityMetrics(meterRegistry, mongoAvailability, lastCallOutcomes);
  }

  @ParameterizedTest
  @EnumSource(Dependency.class)
  void gauge_isNaNBeforeAnythingIsKnown(Dependency dependency) {
    assertThat(availability(meterRegistry, dependency)).isNaN();
  }

  @Test
  void mongodbGauge_isOneWhileMongoIsAvailable() {
    report(mongoAvailability, primary(ADDRESS));

    assertThat(availability(meterRegistry, Dependency.MONGODB)).isEqualTo(1);
  }

  @Test
  void mongodbGauge_isZeroWhileMongoIsUnavailable() {
    report(mongoAvailability, failing(ADDRESS));

    assertThat(availability(meterRegistry, Dependency.MONGODB)).isZero();
  }

  @Test
  void mongodbGauge_followsTheServerChecksNotTheLastCommand() {
    report(mongoAvailability, primary(ADDRESS));
    lastCallOutcomes.recordFailure(Dependency.MONGODB);

    assertThat(availability(meterRegistry, Dependency.MONGODB)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(value = Dependency.class, names = {"S3", "SNS"})
  void awsGauge_isOneAfterASuccessfulCall(Dependency dependency) {
    lastCallOutcomes.recordSuccess(dependency);

    assertThat(availability(meterRegistry, dependency)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(value = Dependency.class, names = {"S3", "SNS"})
  void awsGauge_isZeroAfterAFailedCall(Dependency dependency) {
    lastCallOutcomes.recordFailure(dependency);

    assertThat(availability(meterRegistry, dependency)).isZero();
  }
}
