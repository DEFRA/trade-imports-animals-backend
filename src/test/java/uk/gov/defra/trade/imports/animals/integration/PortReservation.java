package uk.gov.defra.trade.imports.animals.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Reserves an ephemeral TCP port by binding a {@link ServerSocket}, holding it open until
 * {@link #release()} is called. Use instead of {@code TestSocketUtils.findAvailableTcpPort()}
 * when there is a gap between port selection and binding — e.g. the {@code DEFINED_PORT}
 * pattern where Testcontainers start before the Spring context binds.
 *
 * <p>Without the hold, another test's {@code RANDOM_PORT} context (or JaCoCo-triggered class
 * loading that re-orders context creation) can claim the port in the window between selection
 * and bind, causing {@code PortInUseException}.
 */
public record PortReservation(ServerSocket socket, int port) {

    public static PortReservation reserve() {
        try {
            var socket = new ServerSocket(0);
            return new PortReservation(socket, socket.getLocalPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void release() {
        try { socket.close(); } catch (IOException _) { /* best-effort cleanup */ }
    }
}
