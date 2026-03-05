package uk.gov.defra.trade.imports.animals.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for actuator endpoint configuration (production profile).
 * Verifies CDP platform requirements for health checks and endpoint security.
 *
 * <p>Note: Uses TestRestTemplate with RANDOM_PORT to test actual HTTP endpoints
 * including actuator endpoints. MockMvc is not suitable for actuator testing
 * as it only tests the Spring MVC layer.
 */
class HealthCheckConfigIT extends IntegrationBase {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void healthEndpoint_isAccessible() {
    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString())
        .containsAnyOf("application/json", "application/vnd.spring-boot.actuator.v3+json");
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void healthEndpoint_respondsQuickly() {
    // CDP requirement: Health check must respond quickly (< 5 seconds)
    // NO database connectivity checks allowed
    long startTime = System.currentTimeMillis();

    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

    long duration = System.currentTimeMillis() - startTime;

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    // Assert response is very fast (should be < 100ms without DB checks)
    assertThat(duration)
        .as("Health check should respond quickly without database connectivity checks")
        .isLessThan(1000L); // 1 second max, should be much faster
  }

  @Test
  void healthEndpoint_noDetailsExposed() {
    // CDP requirement: show-details: never (production default)
    // Should not expose internal component details
    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).doesNotContain("components").doesNotContain("details");
  }

  @Test
  void healthEndpoint_atRootPath() {
    // Verify it's at /health not /actuator/health
    ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Old path should not exist
    ResponseEntity<String> oldPath = restTemplate.getForEntity("/actuator/health", String.class);
    assertThat(oldPath.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void infoEndpoint_notAccessible() {
    // Production: /info endpoint is not exposed via management.endpoints.web.exposure.include
    // Expected: 404 Not Found (endpoint does not exist in exposed endpoint set)
    ResponseEntity<String> response = restTemplate.getForEntity("/info", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void metricsEndpoint_notAccessible() {
    // Production: /metrics should NOT be exposed
    ResponseEntity<String> response = restTemplate.getForEntity("/metrics", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void envEndpoint_notAccessible() {
    // Production: /env should NOT be exposed (security risk)
    ResponseEntity<String> response = restTemplate.getForEntity("/env", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void actuatorBasePath_isDisabled() {
    // Verify base path is / not /actuator (per CDP requirements)
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
