package uk.gov.defra.trade.imports.animals.operators;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uk.gov.defra.trade.imports.animals.interceptor.TraceIdPropagationInterceptor;

/**
 * Boundary test for {@link OperatorsApiClient}. A real in-JVM HTTP server ({@code
 * com.sun.net.httpserver.HttpServer}) stands in for the operators service so the assertions are on
 * the actual bytes on the wire (request headers) and the actual classification returned for each
 * response — no call-count / interaction assertions, and real socket timeouts.
 */
class OperatorsApiClientTest {

  private static final String TRACE_HEADER = "x-cdp-request-id";

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    MDC.clear();
  }

  private OperatorsApiClient clientFor(HttpHandler handler) {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", handler);
      server.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    RestClient restClient =
        RestClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .requestFactory(readTimeoutFactory())
            .requestInterceptor(new TraceIdPropagationInterceptor(TRACE_HEADER))
            .build();
    return new OperatorsApiClient(restClient);
  }

  private static JdkClientHttpRequestFactory readTimeoutFactory() {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(2));
    return factory;
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Test
  void classify_forwardsCrnAndTraceHeaders_andReadsPathFromContract() {
    AtomicReference<Headers> received = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    OperatorsApiClient client =
        clientFor(
            exchange -> {
              received.set(exchange.getRequestHeaders());
              path.set(exchange.getRequestURI().getPath());
              reply(exchange, 200, "{\"id\":\"OP-1\",\"status\":\"ACTIVE\",\"name\":\"Acme\"}");
            });

    MDC.put("crn", "GBCRN123");
    MDC.put("trace.id", "trace-abc");

    client.classify("OP-1");

    assertThat(path.get()).isEqualTo("/operators/OP-1");
    assertThat(received.get().getFirst("Trade-Imports-Crn")).isEqualTo("GBCRN123");
    assertThat(received.get().getFirst(TRACE_HEADER)).isEqualTo("trace-abc");
  }

  @Test
  void classify_returnsActive_on200WithStatusActive() {
    OperatorsApiClient client =
        clientFor(
            exchange ->
                reply(exchange, 200, "{\"id\":\"OP-1\",\"status\":\"ACTIVE\",\"name\":\"Acme\"}"));

    assertThat(client.classify("OP-1")).isEqualTo(OperatorExistence.ACTIVE);
  }

  @Test
  void classify_returnsDeleted_on200WithStatusDeleted() {
    OperatorsApiClient client =
        clientFor(exchange -> reply(exchange, 200, "{\"id\":\"OP-1\",\"status\":\"DELETED\"}"));

    assertThat(client.classify("OP-1")).isEqualTo(OperatorExistence.DELETED);
  }

  @Test
  void classify_returnsNotFound_on404_andDoesNotConflateWithDeleted() {
    OperatorsApiClient client =
        clientFor(exchange -> reply(exchange, 404, "{\"title\":\"Not found\"}"));

    OperatorExistence result = client.classify("OP-1");

    assertThat(result).isEqualTo(OperatorExistence.NOT_FOUND);
    assertThat(result).isNotEqualTo(OperatorExistence.DELETED);
  }

  @Test
  void classify_returnsUnavailable_onTimeout_andDoesNotConflateWithNotFound() {
    OperatorsApiClient client =
        clientFor(
            exchange -> {
              try {
                Thread.sleep(4000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              reply(exchange, 200, "{\"id\":\"OP-1\",\"status\":\"ACTIVE\"}");
            });

    OperatorExistence result = client.classify("OP-1");

    assertThat(result).isEqualTo(OperatorExistence.UNAVAILABLE);
    assertThat(result).isNotEqualTo(OperatorExistence.NOT_FOUND);
  }

  @Test
  void classify_returnsUnavailable_onConnectionFailure() {
    // Start then immediately stop a server so the port is closed: connection refused.
    OperatorsApiClient client = clientFor(exchange -> reply(exchange, 200, "{}"));
    server.stop(0);
    server = null;

    assertThat(client.classify("OP-1")).isEqualTo(OperatorExistence.UNAVAILABLE);
  }
}
