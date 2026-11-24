package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.connected;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.failing;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.primary;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.report;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.secondary;
import static uk.gov.defra.trade.imports.animals.testsupport.MongoClusterEvents.unchecked;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ServerType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MongoAvailabilityTest {

  private static final ServerAddress FIRST = new ServerAddress("mongo-1", 27017);
  private static final ServerAddress SECOND = new ServerAddress("mongo-2", 27017);

  private final MongoAvailability availability = new MongoAvailability();

  @Test
  void state_isUnknownBeforeAnyServerIsChecked() {
    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNKNOWN);
  }

  @Test
  void state_isUnknownWhileAServerIsNotYetChecked() {
    report(availability, unchecked(FIRST));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNKNOWN);
  }

  @Test
  void state_isUnknownWhenTheDriverMonitorsNoServers() {
    report(availability);

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNKNOWN);
  }

  @Test
  void state_isAvailableWhileAConnectedServerAcceptsWrites() {
    report(availability, failing(FIRST), primary(SECOND));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.AVAILABLE);
  }

  @ParameterizedTest
  @EnumSource(
      value = ServerType.class,
      names = {"REPLICA_SET_PRIMARY", "STANDALONE", "SHARD_ROUTER", "LOAD_BALANCER"})
  void state_shouldBeAvailable_whenAConnectedServerIsOfAWritableType(ServerType type) {
    report(availability, connected(FIRST, type));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.AVAILABLE);
  }

  @Test
  void state_isUnknownWhileOneServerHasFailedAndAnotherIsNotYetChecked() {
    report(availability, failing(FIRST), unchecked(SECOND));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNKNOWN);
  }

  @Test
  void state_isUnavailableOnceEveryServerHasFailedItsCheck() {
    report(availability, failing(FIRST), failing(SECOND));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNAVAILABLE);
  }

  @Test
  void state_isUnavailableWhenTheConnectedServersAreSecondariesAndTheRestHaveFailed() {
    report(availability, failing(FIRST), secondary(SECOND));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNAVAILABLE);
  }

  @Test
  void state_isUnknownWhileASecondaryIsConnectedAndAnotherServerIsNotYetChecked() {
    report(availability, secondary(FIRST), unchecked(SECOND));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNKNOWN);
  }

  @Test
  void state_isUnavailableOnceThePrimaryIsRemoved() {
    report(availability, failing(FIRST), primary(SECOND));

    report(availability, failing(FIRST));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNAVAILABLE);
  }

  @Test
  void state_isAvailableAgainOnceAServerRecovers() {
    report(availability, failing(FIRST));

    report(availability, primary(FIRST));

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.AVAILABLE);
  }

  @Test
  void clusterDescriptionChanged_logsOnceAtWarnNamingTheServersWithTheFailure_whenUnavailable() {
    report(availability, primary(FIRST), primary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, failing(FIRST), failing(SECOND));
              report(availability, failing(FIRST), failing(SECOND));
            });

    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(lines.getFirst().getFormattedMessage())
        .isEqualTo("MongoDB is unavailable, servers [mongo-1:27017, mongo-2:27017]");
    assertThat(lines.getFirst().getThrowableProxy().getMessage()).isEqualTo("refused");
  }

  @Test
  void clusterDescriptionChanged_logsOnceAtWarnNamingTheServersNoneWritable_whenThePrimaryIsLost() {
    report(availability, primary(FIRST), secondary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, failing(FIRST), secondary(SECOND));
              report(availability, failing(FIRST), secondary(SECOND));
            });

    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(lines.getFirst().getFormattedMessage())
        .isEqualTo(
            "MongoDB is unavailable: servers [mongo-1:27017, mongo-2:27017] are connected but"
                + " none accepts writes");
  }

  @Test
  void clusterDescriptionChanged_logsOnceAtInfoNamingTheServers_whenTheDatabaseBecomesAvailable() {
    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, primary(FIRST), unchecked(SECOND));
              report(availability, primary(FIRST), primary(SECOND));
            });

    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst().getLevel()).isEqualTo(Level.INFO);
    assertThat(lines.getFirst().getFormattedMessage())
        .isEqualTo("MongoDB is available, servers [mongo-1:27017, mongo-2:27017]");
  }

  @Test
  void clusterDescriptionChanged_turnsUnknownLoggingOnceAtWarnNamingTheServers_fromAvailable() {
    report(availability, primary(FIRST), primary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, unchecked(FIRST), unchecked(SECOND));
              report(availability, unchecked(FIRST), unchecked(SECOND));
            });

    assertThat(availability.state()).isEqualTo(MongoAvailability.State.UNKNOWN);
    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(lines.getFirst().getFormattedMessage())
        .isEqualTo(
            "MongoDB availability is unknown until every server is checked, servers"
                + " [mongo-1:27017, mongo-2:27017]");
  }

  @Test
  void clusterDescriptionChanged_logsNothing_whileAnotherServerStillAcceptsWrites() {
    report(availability, primary(FIRST), primary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class, () -> report(availability, failing(FIRST), primary(SECOND)));

    assertThat(lines).isEmpty();
  }
}
