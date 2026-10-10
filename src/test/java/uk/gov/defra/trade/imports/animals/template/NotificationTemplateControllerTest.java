package uk.gov.defra.trade.imports.animals.template;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.template.NotificationTemplateController.HEADER_USER_ID;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationTemplateController.class)
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085",
    "outbox.sns.topic-arn=arn:aws:sns:eu-west-2:000000000000:unit-test-outbox.fifo"
})
class NotificationTemplateControllerTest {

    private static final String OWNER_ID = "2100010101";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationTemplateService notificationTemplateService;

    @Test
    void list_shouldReturnTheUsersTemplates() throws Exception {
        // Given
        NotificationTemplateSummary summary = new NotificationTemplateSummary(
            "t-1", "Arena Aintree", "live-animals", Instant.parse("2026-10-09T10:00:00Z"));
        when(notificationTemplateService.listFor(OWNER_ID))
            .thenReturn(new NotificationTemplateListResponse(List.of(summary), 1));

        // When / Then
        mockMvc.perform(get("/templates").header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Arena Aintree"))
            .andExpect(jsonPath("$.items[0].setId").value("live-animals"));
        verify(notificationTemplateService).listFor(OWNER_ID);
    }

    @Test
    void list_shouldReturn400_whenUserIdHeaderIsMissing() throws Exception {
        // When / Then
        mockMvc.perform(get("/templates"))
            .andExpect(status().isBadRequest());
        verify(notificationTemplateService, never()).listFor(any());
    }
}
