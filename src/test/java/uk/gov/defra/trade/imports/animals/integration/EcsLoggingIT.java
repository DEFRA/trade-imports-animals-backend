package uk.gov.defra.trade.imports.animals.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.filter.TraderOrganisationHeaderFilter;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedConsole.captureSystemOut;

@AutoConfigureMockMvc
class EcsLoggingIT extends IntegrationBase {

    private static final String LOGGING_TEST_REF = "LOGGING-TEST";
    private static final String DOCUMENT_UPLOADS_PATH =
        NOTIFICATION_ENDPOINT + "/" + LOGGING_TEST_REF + "/document-uploads";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        seedDraftNotificationOwnedBy(notificationRepository, LOGGING_TEST_REF);
    }

    @Test
    void requestLogging_shouldProduceEcsJsonFormat() throws Exception {
        CapturedConsole console = captureSystemOut();
        try (console) {
            mockMvc.perform(get(DOCUMENT_UPLOADS_PATH)
                            .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, DEFAULT_TRADER_ORG_ID)
                            .header(traceIdHeader, "test-trace-123"))
                    .andExpect(status().isOk());
        }

        JsonNode logJson = requestLogLine(console);

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
        CapturedConsole console = captureSystemOut();
        try (console) {
            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk());
        }

        assertThat(console.text()).doesNotContain("/health");
    }

    @Test
    void missingTraceIdHeader_shouldOmitTraceId() throws Exception {
        CapturedConsole console = captureSystemOut();
        try (console) {
            mockMvc.perform(get(DOCUMENT_UPLOADS_PATH)
                            .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, DEFAULT_TRADER_ORG_ID))
                    .andExpect(status().isOk());
        }

        assertThat(requestLogLine(console).has("trace.id")).isFalse();
    }

    private static JsonNode requestLogLine(CapturedConsole console) {
        return console.jsonLines().stream()
            .filter(line -> line.has("url.full"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No console line has url.full"));
    }
}
