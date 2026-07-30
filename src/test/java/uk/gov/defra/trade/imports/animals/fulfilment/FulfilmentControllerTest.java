package uk.gov.defra.trade.imports.animals.fulfilment;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;

@WebMvcTest(FulfilmentController.class)
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085"
})
class FulfilmentControllerTest {

    private static final String ID = "GBN-AG-26-ABC123";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FulfilmentService fulfilmentService;

    @Test
    void findAll_shouldPassReferenceNumberWithPageAndSort() throws Exception {
        when(fulfilmentService.findAll(2, "createdAt,asc", ID))
            .thenReturn(FulfilmentPageResponse.from(2, 20, 1, List.of()));

        mockMvc.perform(get("/fulfilments")
                .param("page", "2")
                .param("sort", "createdAt,asc")
                .param("referenceNumber", ID))
            .andExpect(status().isOk());

        verify(fulfilmentService).findAll(2, "createdAt,asc", ID);
    }

    @Test
    void submit_shouldAcceptMissingActorBody() throws Exception {
        when(fulfilmentService.submit(ID, "trace-submit", null))
            .thenReturn(fulfilment(FulfilmentStatus.SUBMITTED));

        mockMvc.perform(post("/fulfilments/{id}/submit", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-submit")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(fulfilmentService).submit(ID, "trace-submit", null);
    }

    @Test
    void submit_shouldBindActorBody() throws Exception {
        when(fulfilmentService.submit(eq(ID), eq("trace-submit"), argThat(a -> a != null)))
            .thenReturn(fulfilment(FulfilmentStatus.SUBMITTED));

        mockMvc.perform(post("/fulfilments/{id}/submit", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(actorBody()))
            .andExpect(status().isOk());

        verify(fulfilmentService).submit(
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
        when(fulfilmentService.amend(ID, "trace-amend", null))
            .thenReturn(fulfilment(FulfilmentStatus.AMEND));

        mockMvc.perform(post("/fulfilments/{id}/amend", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-amend")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(fulfilmentService).amend(ID, "trace-amend", null);
    }

    @Test
    void amend_shouldBindActorBody() throws Exception {
        when(fulfilmentService.amend(eq(ID), eq("trace-amend"), argThat(a -> a != null)))
            .thenReturn(fulfilment(FulfilmentStatus.AMEND));

        mockMvc.perform(post("/fulfilments/{id}/amend", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-amend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(actorBody()))
            .andExpect(status().isOk());

        verify(fulfilmentService).amend(
            eq(ID), eq("trace-amend"),
            argThat(a -> "contact-guid-001".equals(a.getId())
                && "dynamics-contact".equals(a.getSource())
                && "B2C".equals(a.getUserType())
                && "Jane Farmer".equals(a.getDisplayName())
                && "org-001".equals(a.getOrganisationId())
                && "org-002".equals(a.getOnBehalfOfOrganisationId())));
    }

    @Test
    void softDelete_shouldAcceptMissingActorBody() throws Exception {
        when(fulfilmentService.softDelete(ID, "trace-withdraw", null))
            .thenReturn(fulfilment(FulfilmentStatus.DELETED));

        mockMvc.perform(post("/fulfilments/{id}/soft-delete", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-withdraw")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        verify(fulfilmentService).softDelete(ID, "trace-withdraw", null);
    }

    @Test
    void softDelete_shouldBindActorBody() throws Exception {
        when(fulfilmentService.softDelete(
            eq(ID), eq("trace-withdraw"), argThat(a -> a != null)))
            .thenReturn(fulfilment(FulfilmentStatus.DELETED));

        mockMvc.perform(post("/fulfilments/{id}/soft-delete", ID)
                .header(NotificationController.HEADER_TRACE_ID, "trace-withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .content(actorBody()))
            .andExpect(status().isOk());

        verify(fulfilmentService).softDelete(
            eq(ID), eq("trace-withdraw"),
            argThat(a -> "contact-guid-001".equals(a.getId())
                && "dynamics-contact".equals(a.getSource())
                && "B2C".equals(a.getUserType())
                && "Jane Farmer".equals(a.getDisplayName())
                && "org-001".equals(a.getOrganisationId())
                && "org-002".equals(a.getOnBehalfOfOrganisationId())));
    }

    private Fulfilment fulfilment(FulfilmentStatus status) {
        return Fulfilment.builder().id(ID).status(status).build();
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
