package uk.gov.defra.trade.imports.animals.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for ECS (Elastic Common Schema) structured logging.
 * Verifies that logs are in JSON format with all required CDP fields.
 */
@AutoConfigureMockMvc
class EcsLoggingIT extends IntegrationBase {

    @Autowired
    private MockMvc mockMvc;

    private final ByteArrayOutputStream outputStreamCaptor = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outputStreamCaptor));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void requestLogging_shouldProduceEcsJsonFormat() throws Exception {
        // Make a request with CDP trace ID header — use an app endpoint so the controller's
        // own INFO log carries the MDC fields we assert on.
        mockMvc.perform(get("/document-uploads/ecs-logging-test-id")
                        .header("x-cdp-request-id", "test-trace-123"))
                .andExpect(status().isNotFound());

        // Capture and parse log output
        String logOutput = outputStreamCaptor.toString(StandardCharsets.UTF_8);

        // Find the first valid JSON log line (skip Spring Boot banner)
        String[] lines = logOutput.split("\n");
        JsonNode logJson = null;
        for (String line : lines) {
            if (line.trim().startsWith("{")) {
                logJson = objectMapper.readTree(line);

                // Find a line that has our MDC fields (request log)
                if (logJson.has("url.full")) {
                    break;
                }
            }
        }

        assertThat(logJson).isNotNull();

        // Verify ECS required fields
        assertThat(logJson.has("@timestamp")).isTrue();
        assertThat(logJson.has("log.level")).isTrue();
        assertThat(logJson.has("message")).isTrue();

        // Verify CDP required fields from MDC
        assertThat(logJson.has("trace.id")).isTrue();
        assertThat(logJson.get("trace.id").asText()).isEqualTo("test-trace-123");

        assertThat(logJson.has("http.request.method")).isTrue();
        assertThat(logJson.get("http.request.method").asText()).isEqualTo("GET");

        assertThat(logJson.has("url.full")).isTrue();
        assertThat(logJson.get("url.full").asText()).contains("/document-uploads/ecs-logging-test-id");

        // Verify service metadata
        assertThat(logJson.has("service.name")).isTrue();
        assertThat(logJson.has("service.version")).isTrue();
    }

    @Test
    void healthEndpoint_shouldBeFilteredFromLogs() throws Exception {
        // Clear previous output
        outputStreamCaptor.reset();

        // Make request to /health endpoint
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());

        String logOutput = outputStreamCaptor.toString(StandardCharsets.UTF_8);

        // Verify /health is NOT in any log output
        assertThat(logOutput).doesNotContain("/health");
    }

    @Test
    void missingTraceIdHeader_shouldLeaveTraceIdEmpty() throws Exception {
        // Clear previous output
        outputStreamCaptor.reset();

        // Make request WITHOUT x-cdp-request-id header
        mockMvc.perform(get("/document-uploads/ecs-logging-test-id"))
                .andExpect(status().isNotFound());

        String logOutput = outputStreamCaptor.toString(StandardCharsets.UTF_8);

        // Parse log output
        String[] lines = logOutput.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("{")) {
                JsonNode logJson = objectMapper.readTree(line);

                if (logJson.has("url.full")) {
                    // trace.id should not be present when header is missing
                    assertThat(logJson.has("trace.id")).isFalse();
                    break;
                }
            }
        }
    }
}
