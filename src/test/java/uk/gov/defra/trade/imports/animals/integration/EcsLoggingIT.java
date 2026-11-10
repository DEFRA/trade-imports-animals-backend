package uk.gov.defra.trade.imports.animals.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.filter.TraderOrganisationHeaderFilter;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EcsLoggingIT extends IntegrationBase {

    private static final String LOGGING_TEST_REF = "LOGGING-TEST";
    private static final String DOCUMENT_UPLOADS_PATH =
        "/notifications/" + LOGGING_TEST_REF + "/document-uploads";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    private final ByteArrayOutputStream outputStreamCaptor = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        seedDraftNotificationOwnedBy(notificationRepository, LOGGING_TEST_REF);
        System.setOut(new PrintStream(outputStreamCaptor));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void requestLogging_shouldProduceEcsJsonFormat() throws Exception {
        mockMvc.perform(get(DOCUMENT_UPLOADS_PATH)
                        .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, DEFAULT_TRADER_ORG_ID)
                        .header("x-cdp-request-id", "test-trace-123"))
                .andExpect(status().isOk());

        JsonNode logJson = requestLogLine(outputStreamCaptor.toString(StandardCharsets.UTF_8));

        assertThat(logJson.has("@timestamp")).isTrue();
        assertThat(logJson.has("log.level")).isTrue();
        assertThat(logJson.has("message")).isTrue();

        assertThat(logJson.has("trace.id")).isTrue();
        assertThat(logJson.get("trace.id").asText()).isEqualTo("test-trace-123");

        assertThat(logJson.has("http.request.method")).isTrue();
        assertThat(logJson.get("http.request.method").asText()).isEqualTo("GET");

        assertThat(logJson.has("url.full")).isTrue();
        assertThat(logJson.get("url.full").asText()).contains(DOCUMENT_UPLOADS_PATH);

        assertThat(logJson.has("service.name")).isTrue();
        assertThat(logJson.has("service.version")).isTrue();
    }

    @Test
    void healthEndpoint_shouldBeFilteredFromLogs() throws Exception {
        outputStreamCaptor.reset();

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());

        assertThat(outputStreamCaptor.toString(StandardCharsets.UTF_8)).doesNotContain("/health");
    }

    @Test
    void missingTraceIdHeader_shouldOmitTraceId() throws Exception {
        outputStreamCaptor.reset();

        mockMvc.perform(get(DOCUMENT_UPLOADS_PATH)
                        .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, DEFAULT_TRADER_ORG_ID))
                .andExpect(status().isOk());

        JsonNode logJson = requestLogLine(outputStreamCaptor.toString(StandardCharsets.UTF_8));
        assertThat(logJson).as("Expected a log line with url.full field").isNotNull();
        assertThat(logJson.has("trace.id")).isFalse();
    }

    private static JsonNode requestLogLine(String logOutput) throws IOException {
        for (String line : logOutput.split("\n")) {
            if (!line.trim().startsWith("{")) {
                continue;
            }
            JsonNode candidate = objectMapper.readTree(line);
            if (candidate.has("url.full")) {
                return candidate;
            }
        }
        return null;
    }
}
