package uk.gov.defra.trade.imports.animals.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.assertOneLine;
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
  void state_shouldBeUnknown_beforeAnyServerIsChecked() {
    assertThat(availability.state()).isEqualTo(Availability.UNKNOWN);
  }

  @Test
  void state_shouldBeUnknown_whileAServerIsNotYetChecked() {
    report(availability, unchecked(FIRST));

    assertThat(availability.state()).isEqualTo(Availability.UNKNOWN);
  }

  @Test
  void state_shouldBeUnknown_whenTheDriverMonitorsNoServers() {
    report(availability);

    assertThat(availability.state()).isEqualTo(Availability.UNKNOWN);
  }

  @Test
  void state_shouldBeAvailable_whileAConnectedServerAcceptsWrites() {
    report(availability, failing(FIRST), primary(SECOND));

    assertThat(availability.state()).isEqualTo(Availability.AVAILABLE);
  }

  @ParameterizedTest
  @EnumSource(
      value = ServerType.class,
      names = {"REPLICA_SET_PRIMARY", "STANDALONE", "SHARD_ROUTER", "LOAD_BALANCER"})
  void state_shouldBeAvailable_whenAConnectedServerIsOfAWritableType(ServerType type) {
    report(availability, connected(FIRST, type));

    assertThat(availability.state()).isEqualTo(Availability.AVAILABLE);
  }

  @Test
  void state_shouldBeUnknown_whileOneServerHasFailedAndAnotherIsNotYetChecked() {
    report(availability, failing(FIRST), unchecked(SECOND));

    assertThat(availability.state()).isEqualTo(Availability.UNKNOWN);
  }

  @Test
  void state_shouldBeUnavailable_onceEveryServerHasFailedItsCheck() {
    report(availability, failing(FIRST), failing(SECOND));

    assertThat(availability.state()).isEqualTo(Availability.UNAVAILABLE);
  }

  @Test
  void state_shouldBeUnavailable_whenTheConnectedServersAreSecondariesAndTheRestHaveFailed() {
    report(availability, failing(FIRST), secondary(SECOND));

    assertThat(availability.state()).isEqualTo(Availability.UNAVAILABLE);
  }

  @Test
  void state_shouldBeUnknown_whileASecondaryIsConnectedAndAnotherServerIsNotYetChecked() {
    report(availability, secondary(FIRST), unchecked(SECOND));

    assertThat(availability.state()).isEqualTo(Availability.UNKNOWN);
  }

  @Test
  void state_shouldBeUnavailable_onceThePrimaryIsRemoved() {
    report(availability, failing(FIRST), primary(SECOND));

    report(availability, failing(FIRST));

    assertThat(availability.state()).isEqualTo(Availability.UNAVAILABLE);
  }

  @Test
  void state_shouldBeAvailableAgain_onceAServerRecovers() {
    report(availability, failing(FIRST));

    report(availability, primary(FIRST));

    assertThat(availability.state()).isEqualTo(Availability.AVAILABLE);
  }

  @Test
  void clusterDescriptionChanged_shouldWarnOnceWithTheFailure_whenUnavailable() {
    report(availability, primary(FIRST), primary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, failing(FIRST), failing(SECOND));
              report(availability, failing(FIRST), failing(SECOND));
            });

    ILoggingEvent line =
        assertOneLine(
            lines,
            Level.WARN,
            "MongoDB is unavailable, no server accepts writes: connected servers [], failed"
                + " servers [mongo-1:27017, mongo-2:27017]");
    assertThat(line.getThrowableProxy().getMessage()).isEqualTo("refused");
  }

  @Test
  void clusterDescriptionChanged_shouldWarnOnceNamingTheConnectedAndFailedServersWithTheFailure_whenThePrimaryIsLost() {
    report(availability, primary(FIRST), secondary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, failing(FIRST), secondary(SECOND));
              report(availability, failing(FIRST), secondary(SECOND));
            });

    ILoggingEvent line =
        assertOneLine(
            lines,
            Level.WARN,
            "MongoDB is unavailable, no server accepts writes: connected servers"
                + " [mongo-2:27017], failed servers [mongo-1:27017]");
    assertThat(line.getThrowableProxy().getMessage()).isEqualTo("refused");
  }

  @Test
  void clusterDescriptionChanged_shouldLogOnceAtInfo_whenTheDatabaseBecomesAvailable() {
    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, primary(FIRST), unchecked(SECOND));
              report(availability, primary(FIRST), primary(SECOND));
            });

    assertOneLine(
        lines, Level.INFO, "MongoDB is available, servers [mongo-1:27017, mongo-2:27017]");
  }

  @Test
  void clusterDescriptionChanged_shouldWarnOnce_whenAvailabilityTurnsUnknown() {
    report(availability, primary(FIRST), primary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class,
            () -> {
              report(availability, unchecked(FIRST), unchecked(SECOND));
              report(availability, unchecked(FIRST), unchecked(SECOND));
            });

    assertThat(availability.state()).isEqualTo(Availability.UNKNOWN);
    assertOneLine(
        lines,
        Level.WARN,
        "MongoDB availability is unknown until every server is checked, servers"
            + " [mongo-1:27017, mongo-2:27017]");
  }

  @Test
  void clusterDescriptionChanged_shouldLogNothing_whileAnotherServerStillAcceptsWrites() {
    report(availability, primary(FIRST), primary(SECOND));

    List<ILoggingEvent> lines =
        loggedBy(
            MongoAvailability.class, () -> report(availability, failing(FIRST), primary(SECOND)));

    assertThat(lines).isEmpty();
  }
}
