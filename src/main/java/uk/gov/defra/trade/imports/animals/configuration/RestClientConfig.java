package uk.gov.defra.trade.imports.animals.configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uk.gov.defra.trade.imports.animals.interceptor.TraceIdPropagationInterceptor;

/**
 * Builds outbound HTTP clients on the CDP trust store with trace propagation.
 *
 * <p>Retained with no consumer in this service: see {@code docs/design-notes.md}.
 */
@Configuration
@Slf4j
public class RestClientConfig {

  private final ClientHttpRequestFactory customRequestFactory;
  private final TraceIdPropagationInterceptor traceIdInterceptor;
  private final SSLContext customSslContext;

  public RestClientConfig(
      TraceIdPropagationInterceptor traceIdInterceptor,
      SSLContext customSslContext) {
    log.info("Configuring HTTP clients with custom SSL context and trace ID propagation");
    this.customSslContext = customSslContext;
    this.traceIdInterceptor = traceIdInterceptor;
    this.customRequestFactory = requestFactory(Duration.ofSeconds(10), Duration.ofSeconds(30));
  }

  private ClientHttpRequestFactory requestFactory(Duration connect, Duration read) {
    HttpClient httpClient = HttpClient.newBuilder()
        .sslContext(customSslContext)
        .connectTimeout(connect)
        .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(read);
    return factory;
  }

  /**
   * A RestClient.Builder on the custom SSL context and the trace-id interceptor, prototype-scoped
   * so a {@code baseUrl} set by one consumer does not reach another.
   */
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  public RestClient.Builder restClientBuilder() {
    log.debug("Creating RestClient.Builder with custom SSL context and trace ID propagation");
    return RestClient.builder()
        .requestFactory(customRequestFactory)
        .requestInterceptor(traceIdInterceptor);
  }

}
