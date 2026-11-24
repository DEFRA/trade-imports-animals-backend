package uk.gov.defra.trade.imports.animals.testsupport;

import com.mongodb.MongoSocketOpenException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ClusterType;
import com.mongodb.connection.ServerConnectionState;
import com.mongodb.connection.ServerDescription;
import com.mongodb.connection.ServerType;
import com.mongodb.event.ClusterDescriptionChangedEvent;
import com.mongodb.event.ClusterListener;
import java.util.List;

public final class MongoClusterEvents {

  private MongoClusterEvents() {}

  public static void report(ClusterListener listener, ServerDescription... servers) {
    ClusterDescription description =
        new ClusterDescription(
            ClusterConnectionMode.MULTIPLE, ClusterType.REPLICA_SET, List.of(servers));
    ClusterDescription previous =
        new ClusterDescription(ClusterConnectionMode.MULTIPLE, ClusterType.UNKNOWN, List.of());
    listener.clusterDescriptionChanged(
        new ClusterDescriptionChangedEvent(new ClusterId(), description, previous));
  }

  public static ServerDescription primary(ServerAddress address) {
    return connected(address, ServerType.REPLICA_SET_PRIMARY);
  }

  public static ServerDescription secondary(ServerAddress address) {
    return connected(address, ServerType.REPLICA_SET_SECONDARY);
  }

  public static ServerDescription failing(ServerAddress address) {
    return ServerDescription.builder()
        .address(address)
        .state(ServerConnectionState.CONNECTING)
        .exception(new MongoSocketOpenException("refused", address))
        .build();
  }

  public static ServerDescription unchecked(ServerAddress address) {
    return ServerDescription.builder()
        .address(address)
        .state(ServerConnectionState.CONNECTING)
        .build();
  }

  public static ServerDescription connected(ServerAddress address, ServerType type) {
    return ServerDescription.builder()
        .address(address)
        .state(ServerConnectionState.CONNECTED)
        .ok(true)
        .type(type)
        .build();
  }
}
