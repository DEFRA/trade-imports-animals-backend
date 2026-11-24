package uk.gov.defra.trade.imports.animals.metrics;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ServerConnectionState;
import com.mongodb.connection.ServerDescription;
import com.mongodb.event.ClusterDescriptionChangedEvent;
import com.mongodb.event.ClusterListener;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Whether MongoDB is available, from the servers the driver monitors: available while a connected
 * server accepts writes (a primary, standalone, mongos or load balancer), unavailable once every
 * server has been checked and none accepts writes, and unknown while there are none or one has not
 * been checked yet. Logs each change of state, naming the servers monitored.
 */
@Slf4j
@Component
public class MongoAvailability implements ClusterListener {

  /** What the driver's checks say of the database. */
  public enum State {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN
  }

  private volatile State state = State.UNKNOWN;

  @Override
  public synchronized void clusterDescriptionChanged(ClusterDescriptionChangedEvent event) {
    List<ServerDescription> servers = event.getNewDescription().getServerDescriptions();
    State reported = stateOf(servers);
    if (reported == state) {
      return;
    }
    state = reported;
    switch (reported) {
      case AVAILABLE -> log.info("MongoDB is available, servers {}", addressesOf(servers));
      case UNAVAILABLE -> logUnavailable(servers);
      case UNKNOWN ->
          log.warn(
              "MongoDB availability is unknown until every server is checked, servers {}",
              addressesOf(servers));
    }
  }

  private static State stateOf(List<ServerDescription> servers) {
    if (servers.stream().anyMatch(MongoAvailability::acceptsWrites)) {
      return State.AVAILABLE;
    }
    if (!servers.isEmpty() && servers.stream().allMatch(MongoAvailability::isChecked)) {
      return State.UNAVAILABLE;
    }
    return State.UNKNOWN;
  }

  private static void logUnavailable(List<ServerDescription> servers) {
    if (servers.stream().anyMatch(MongoAvailability::isConnected)) {
      log.warn(
          "MongoDB is unavailable: servers {} are connected but none accepts writes",
          addressesOf(servers));
    } else {
      log.warn("MongoDB is unavailable, servers {}", addressesOf(servers), firstFailure(servers));
    }
  }

  private static boolean acceptsWrites(ServerDescription server) {
    return isConnected(server) && server.isPrimary();
  }

  private static boolean isChecked(ServerDescription server) {
    return isConnected(server) || server.getException() != null;
  }

  private static boolean isConnected(ServerDescription server) {
    return server.getState() == ServerConnectionState.CONNECTED;
  }

  private static List<ServerAddress> addressesOf(List<ServerDescription> servers) {
    return servers.stream().map(ServerDescription::getAddress).toList();
  }

  private static Throwable firstFailure(List<ServerDescription> servers) {
    return servers.stream()
        .map(ServerDescription::getException)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /**
   * The database's state as of the driver's latest checks.
   *
   * @return the state
   */
  public State state() {
    return state;
  }
}
