package uk.gov.defra.trade.imports.animals.template;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.template.NotificationTemplateController.HEADER_USER_ID;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@WebMvcTest(NotificationTemplateController.class)
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085",
    "outbox.sns.topic-arn=arn:aws:sns:eu-west-2:000000000000:unit-test-outbox.fifo"
})
class NotificationTemplateControllerTest {

    private static final String OWNER_ID = "2100010101";
    private static final String TEMPLATE_ID = "6650f1c2a1b2c3d4e5f60718";
    private static final String START_BODY =
        "{\"setId\":\"live-animals\",\"name\":\"Weekly cattle\",\"organisationId\":\"5900001\"}";
    private static final String DETAILS_BODY = "{\"setId\":\"live-animals\",\"name\":\"Weekly\"}";
    private static final String FULFILMENTS_BODY = "{\"fulfilments\":[]}";

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

    @Test
    void start_shouldReturn201WithTheTemplate() throws Exception {
        // Given
        when(notificationTemplateService.start(eq(OWNER_ID), any(StartTemplateRequest.class)))
            .thenReturn(response());

        // When / Then
        mockMvc.perform(post("/templates").header(HEADER_USER_ID, OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(TEMPLATE_ID))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void start_shouldReturn400_whenTheSetIsNotOneThatStoresTemplates() throws Exception {
        // When / Then
        mockMvc.perform(post("/templates").header(HEADER_USER_ID, OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"setId\":\"plants\"}"))
            .andExpect(status().isBadRequest());
        verify(notificationTemplateService, never()).start(any(), any());
    }

    @Test
    void get_shouldReturnTheTemplate() throws Exception {
        // Given
        when(notificationTemplateService.get(OWNER_ID, TEMPLATE_ID)).thenReturn(response());

        // When / Then
        mockMvc.perform(get("/templates/" + TEMPLATE_ID).header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Weekly cattle"));
    }

    @Test
    void get_shouldReturn404_whenTheTemplateIsNotFound() throws Exception {
        // Given
        when(notificationTemplateService.get(OWNER_ID, TEMPLATE_ID))
            .thenThrow(new NotFoundException("Template not found"));

        // When / Then
        mockMvc.perform(get("/templates/" + TEMPLATE_ID).header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_shouldReturn400_whenTheIdIsNotTwentyFourHexCharacters() throws Exception {
        // When / Then
        mockMvc.perform(get("/templates/not-an-id").header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isBadRequest());
        verify(notificationTemplateService, never()).get(any(), any());
    }

    @Test
    void updateDetails_shouldReturn200() throws Exception {
        // Given
        when(notificationTemplateService.updateDetails(
            eq(OWNER_ID), eq(TEMPLATE_ID), any(TemplateDetailsRequest.class)))
            .thenReturn(response());

        // When / Then
        mockMvc.perform(put("/templates/" + TEMPLATE_ID + "/details")
                .header(HEADER_USER_ID, OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON).content(DETAILS_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void replaceFulfilments_shouldReturn200() throws Exception {
        // Given
        when(notificationTemplateService.replaceFulfilments(
            eq(OWNER_ID), eq(TEMPLATE_ID), any(TemplateFulfilmentsRequest.class)))
            .thenReturn(response());

        // When / Then
        mockMvc.perform(put("/templates/" + TEMPLATE_ID + "/fulfilments")
                .header(HEADER_USER_ID, OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON).content(FULFILMENTS_BODY))
            .andExpect(status().isOk());
    }

    @Test
    void save_shouldReturn200WithTheSavedTemplate() throws Exception {
        // Given
        when(notificationTemplateService.save(OWNER_ID, TEMPLATE_ID)).thenReturn(response());

        // When / Then
        mockMvc.perform(post("/templates/" + TEMPLATE_ID + "/save")
                .header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Weekly cattle"));
    }

    @Test
    void save_shouldReturn404_whenTheTemplateIsNotFound() throws Exception {
        // Given
        when(notificationTemplateService.save(OWNER_ID, TEMPLATE_ID))
            .thenThrow(new NotFoundException("Template not found"));

        // When / Then
        mockMvc.perform(post("/templates/" + TEMPLATE_ID + "/save")
                .header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void save_shouldReturn400_whenTheIdIsNotTwentyFourHexCharacters() throws Exception {
        // When / Then
        mockMvc.perform(post("/templates/not-an-id/save").header(HEADER_USER_ID, OWNER_ID))
            .andExpect(status().isBadRequest());
        verify(notificationTemplateService, never()).save(any(), any());
    }

    @Test
    void everyNewRoute_shouldReturn400_whenUserIdHeaderIsMissing() throws Exception {
        // When / Then
        mockMvc.perform(post("/templates")
                .contentType(MediaType.APPLICATION_JSON).content(START_BODY))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/templates/" + TEMPLATE_ID))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/templates/" + TEMPLATE_ID + "/details")
                .contentType(MediaType.APPLICATION_JSON).content(DETAILS_BODY))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/templates/" + TEMPLATE_ID + "/fulfilments")
                .contentType(MediaType.APPLICATION_JSON).content(FULFILMENTS_BODY))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/templates/" + TEMPLATE_ID + "/save"))
            .andExpect(status().isBadRequest());
    }

    private static NotificationTemplateResponse response() {
        return new NotificationTemplateResponse(TEMPLATE_ID, "Weekly cattle", "live-animals",
            TemplateStatus.IN_PROGRESS, 0L, Instant.parse("2026-10-09T10:00:00Z"), null,
            List.of());
    }
}
