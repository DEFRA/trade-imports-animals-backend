package uk.gov.defra.trade.imports.animals.configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookConfig;
import uk.gov.defra.trade.imports.animals.interceptor.TraceIdPropagationInterceptor;

/**
 * Configuration for HTTP clients with custom SSL/TLS certificates and trace ID propagation.
 *
 * <p>Provides both RestClient (modern, Spring Boot 3.2+) and RestTemplate (legacy) configured with:
 *
 * <ul>
 *   <li>Custom SSLContext (Default JVM trust store + CDP TRUSTSTORE_* certificates)
 *   <li>TraceIdPropagationInterceptor (propagates x-cdp-request-id to all outbound calls)
 * </ul>
 *
 * <p>This configuration uses Java's built-in HttpClient (JDK 11+) with custom SSL, requiring zero
 * external dependencies (no Apache HttpClient needed).
 *
 * <p>This ensures that outbound HTTP requests to CDP internal services with custom CA certificates
 * are trusted and that distributed tracing works across service boundaries.
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
   * Creates a RestClient.Builder configured with custom SSL context and trace ID propagation.
   * RestClient is the modern, recommended HTTP client for Spring Boot 3.2+.
   *
   * <p>Prototype-scoped so each injection point receives a fresh builder; {@code baseUrl} and other
   * mutating calls on one consumer do not affect others.
   *
   * <p>Usage:
   *
   * <pre>{@code
   * @Service
   * public class MyService {
   *     private final RestClient restClient;
   *
   *     public MyService(RestClient.Builder builder) {
   *         this.restClient = builder
   *             .baseUrl("https://my-service.example.com")
   *             .build();
   *     }
   * }
   * }</pre>
   */
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  public RestClient.Builder restClientBuilder() {
    log.debug("Creating RestClient.Builder with custom SSL context and trace ID propagation");
    return RestClient.builder()
        .requestFactory(customRequestFactory)
        .requestInterceptor(traceIdInterceptor);
  }

  /**
   * Default RestClient bean configured with custom SSL context and trace ID propagation.
   * Applications can inject this bean directly.
   */
  @Bean
  public RestClient restClient(RestClient.Builder builder) {
    return builder.build();
  }

  /**
   * Creates a RestTemplateBuilder configured with custom SSL context and trace ID propagation.
   * RestTemplate is the legacy HTTP client, maintained for backward compatibility.
   *
   * <p>Use this builder to create RestTemplate instances that trust CDP certificates and propagate
   * trace IDs.
   */
  @Bean
  public RestTemplateBuilder restTemplateBuilder() {
    log.debug("Creating RestTemplateBuilder with custom SSL context and trace ID propagation");
    return new RestTemplateBuilder()
        .requestFactory(() -> customRequestFactory)
        .interceptors(traceIdInterceptor);
  }

  /**
   * Default RestTemplate bean configured with custom SSL context and trace ID propagation.
   * Applications can inject this bean or use the RestTemplateBuilder.
   */
  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
  }

  /**
   * RestClient pre-configured with the cdp-uploader base URL.
   *
   * <p>Uses a prototype-scoped {@link RestClient.Builder} so calling {@code baseUrl} here does not
   * affect other consumers of the shared builder bean.
   */
  @Bean
  public RestClient cdpUploaderRestClient(RestClient.Builder builder, CdpConfig cdpConfig) {
    log.debug("Creating cdpUploaderRestClient with base URL: {}", cdpConfig.uploader().baseUrl());
    return builder.baseUrl(cdpConfig.uploader().baseUrl()).build();
  }

  /**
   * RestClient pre-configured with the address-book base URL, used to resolve referenced parties on
   * read. Uses a short connect/read timeout so a slow address book fails fast on the notification
   * read path instead of holding a servlet thread for the shared 30s client timeout.
   */
  @Bean
  public RestClient addressBookRestClient(
      RestClient.Builder builder, AddressBookConfig addressBookConfig) {
    log.debug("Creating addressBookRestClient with base URL: {}", addressBookConfig.baseUrl());
    return builder
        .requestFactory(requestFactory(
            addressBookConfig.connectTimeout(), addressBookConfig.readTimeout()))
        .baseUrl(addressBookConfig.baseUrl())
        .build();
  }
}
