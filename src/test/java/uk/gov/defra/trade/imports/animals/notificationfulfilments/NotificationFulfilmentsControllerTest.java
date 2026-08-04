package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;

@WebMvcTest(NotificationFulfilmentsController.class)
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085"
})
class NotificationFulfilmentsControllerTest {

    private static final String ID = "GBN-AG-26-ABC123";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationFulfilmentsService notificationFulfilmentsService;

    @Test
    void submit_shouldAcceptMissingActorBody() throws Exception {
        when(notificationFulfilmentsService.submit(ID, "trace-submit", null))
            .thenReturn(fulfilment(NotificationFulfilmentsStatus.SUBMITTED));

        mockMvc.perform(post("/notification-fulfilments/{id}/submit", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-submit")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(notificationFulfilmentsService).submit(ID, "trace-submit", null);
    }

    @Test
    void submit_shouldBindActorBody() throws Exception {
        when(notificationFulfilmentsService.submit(eq(ID), eq("trace-submit"), argThat(a -> a != null)))
            .thenReturn(fulfilment(NotificationFulfilmentsStatus.SUBMITTED));

        mockMvc.perform(post("/notification-fulfilments/{id}/submit", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(actorBody()))
            .andExpect(status().isOk());

        verify(notificationFulfilmentsService).submit(
            eq(ID), eq("trace-submit"),
            argThat(a -> "contact-guid-001".equals(a.getId())
                && "dynamics-contact".equals(a.getSource())
                && "B2C".equals(a.getUserType())
                && "Jane Farmer".equals(a.getDisplayName())
                && "org-001".equals(a.getOrganisationId())
                && "org-002".equals(a.getOnBehalfOfOrganisationId())));
    }

    @Test
    void amend_shouldAcceptMissingActorBody() throws Exception {
        when(notificationFulfilmentsService.amend(ID, "trace-amend", null))
            .thenReturn(fulfilment(NotificationFulfilmentsStatus.AMEND));

        mockMvc.perform(post("/notification-fulfilments/{id}/amend", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-amend")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(notificationFulfilmentsService).amend(ID, "trace-amend", null);
    }

    @Test
    void amend_shouldBindActorBody() throws Exception {
        when(notificationFulfilmentsService.amend(eq(ID), eq("trace-amend"), argThat(a -> a != null)))
            .thenReturn(fulfilment(NotificationFulfilmentsStatus.AMEND));

        mockMvc.perform(post("/notification-fulfilments/{id}/amend", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-amend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(actorBody()))
            .andExpect(status().isOk());

        verify(notificationFulfilmentsService).amend(
            eq(ID), eq("trace-amend"),
            argThat(a -> "contact-guid-001".equals(a.getId())
                && "dynamics-contact".equals(a.getSource())
                && "B2C".equals(a.getUserType())
                && "Jane Farmer".equals(a.getDisplayName())
                && "org-001".equals(a.getOrganisationId())
                && "org-002".equals(a.getOnBehalfOfOrganisationId())));
    }

    private NotificationFulfilments fulfilment(NotificationFulfilmentsStatus status) {
        return NotificationFulfilments.builder().id(ID).status(status).build();
    }

    private String actorBody() {
        return """
            {
                "id": "contact-guid-001",
                "source": "dynamics-contact",
                "userType": "B2C",
                "displayName": "Jane Farmer",
                "organisationId": "org-001",
                "onBehalfOfOrganisationId": "org-002"
            }
            """;
    }
}
