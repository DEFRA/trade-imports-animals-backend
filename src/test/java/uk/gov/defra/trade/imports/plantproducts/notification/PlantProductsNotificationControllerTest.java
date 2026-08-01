package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedDto;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedNotification;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.refNumber;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentMapper;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentService;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsBadRequestException;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsExceptionHandler;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;

@WebMvcTest(PlantProductsNotificationController.class)
@ContextConfiguration(classes = {
    PlantProductsNotificationController.class,
    PlantProductsExceptionHandler.class
})
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085"
})
class PlantProductsNotificationControllerTest {

    private static final String REFERENCE = "GBN-PP-26-ABC001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PlantProductsNotificationService notificationService;

    @MockitoBean
    private PlantProductsAccompanyingDocumentService accompanyingDocumentService;

    @MockitoBean
    private PlantProductsNotificationMapper notificationMapper;

    @MockitoBean
    private PlantProductsAccompanyingDocumentMapper accompanyingDocumentMapper;

    @Nested
    class Create {

        @Test
        void post_shouldReturn201LocationAndBody() throws Exception {
            // Given
            PlantProductsNotification created = fullyPopulatedNotification();
            when(notificationService.create(any())).thenReturn(created);

            // When & Then
            mockMvc.perform(post("/plant-products/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fullyPopulatedDto())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                    "http://localhost:8085/plant-products/notifications/" + REFERENCE))
                .andExpect(jsonPath("$.referenceNumber").value(REFERENCE))
                .andExpect(jsonPath("$.commodity.commodityComplement[0].species[0].varieties[0].varietyClass")
                    .value("CLASS_I"));
        }

        @Test
        void post_shouldMapBadRequestToProblemBody() throws Exception {
            // Given
            when(notificationService.create(any()))
                .thenThrow(new PlantProductsBadRequestException("body reference is forbidden"));

            // When & Then
            mockMvc.perform(post("/plant-products/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fullyPopulatedDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("body reference is forbidden"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
        }
    }

    @Nested
    class Replace {

        @Test
        void put_shouldReturn200WhenExistingNotificationIsReplaced() throws Exception {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(REFERENCE);
            when(notificationService.replace(eq(REFERENCE), any()))
                .thenReturn(new PlantProductsNotificationService.ReplaceResult(
                    fullyPopulatedNotification(), false));

            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REFERENCE));
        }

        @Test
        void put_shouldReturn201AndLocationWhenUpsertCreates() throws Exception {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(REFERENCE);
            when(notificationService.replace(eq(REFERENCE), any()))
                .thenReturn(new PlantProductsNotificationService.ReplaceResult(
                    fullyPopulatedNotification(), true));

            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                    "http://localhost:8085/plant-products/notifications/" + REFERENCE));
        }

        @Test
        void put_shouldReturn400WhenServiceRejectsMismatch() throws Exception {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(refNumber("0THER1"));
            when(notificationService.replace(eq(REFERENCE), any()))
                .thenThrow(new PlantProductsBadRequestException("reference mismatch"));

            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("reference mismatch"));
        }
    }

    @Nested
    class FindByReference {

        @Test
        void get_shouldEmbedAccompanyingDocumentsArray() throws Exception {
            // Given
            PlantProductsNotification entity = fullyPopulatedNotification();
            PlantProductsNotificationResponse response = PlantProductsNotificationResponse.builder()
                .id(entity.getId())
                .referenceNumber(REFERENCE)
                .status(entity.getStatus())
                .build();
            when(notificationService.find(REFERENCE)).thenReturn(Optional.of(entity));
            when(notificationMapper.toResponse(entity)).thenReturn(response);
            when(accompanyingDocumentService.list(REFERENCE)).thenReturn(List.of());

            // When & Then
            mockMvc.perform(get("/plant-products/notifications/{reference-number}", REFERENCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNumber").value(REFERENCE))
                .andExpect(jsonPath("$.accompanyingDocuments").isArray());
        }

        @Test
        void get_shouldReturn404ProblemWhenUnknown() throws Exception {
            // Given
            when(notificationService.find(REFERENCE)).thenReturn(Optional.empty());

            // When & Then
            mockMvc.perform(get("/plant-products/notifications/{reference-number}", REFERENCE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
        }
    }

    @Test
    void getList_shouldReturnPageEnvelopeAndPassQueryParameters() throws Exception {
        // Given
        PlantProductsNotificationDto dto = fullyPopulatedDto();
        dto.setReferenceNumber(REFERENCE);
        PlantProductsNotificationPageResponse response = new PlantProductsNotificationPageResponse(
            List.of(dto), 2, 25, 26, 2);
        when(notificationService.findAll(2, "createdAt,asc", REFERENCE)).thenReturn(response);

        // When & Then
        mockMvc.perform(get("/plant-products/notifications")
                .param("page", "2")
                .param("sort", "createdAt,asc")
                .param("referenceNumber", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content[0].referenceNumber").value(REFERENCE))
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.pageSize").value(25))
            .andExpect(jsonPath("$.totalElements").value(26))
            .andExpect(jsonPath("$.totalPages").value(2));
        verify(notificationService).findAll(2, "createdAt,asc", REFERENCE);
    }

    @Nested
    class ChangeStatus {

        @Test
        void putStatus_shouldReturn200() throws Exception {
            // Given
            PlantProductsNotification submitted = fullyPopulatedNotification();
            when(notificationService.changeStatus(eq(REFERENCE), any())).thenReturn(submitted);

            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}/status", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        }

        @Test
        void putStatus_shouldReturn400WhenStatusMissing() throws Exception {
            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}/status", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest());
        }

        @Test
        void putStatus_shouldReturn400ForIllegalTransition() throws Exception {
            // Given
            when(notificationService.changeStatus(eq(REFERENCE), any()))
                .thenThrow(new PlantProductsBadRequestException("illegal transition"));

            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}/status", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("illegal transition"));
        }

        @Test
        void putStatus_shouldReturn404ForUnknownReference() throws Exception {
            // Given
            when(notificationService.changeStatus(eq(REFERENCE), any()))
                .thenThrow(new PlantProductsNotFoundException("unknown reference"));

            // When & Then
            mockMvc.perform(put("/plant-products/notifications/{reference-number}/status", REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Copy {

        @Test
        void postCopy_shouldReturn201AndLocation() throws Exception {
            // Given
            PlantProductsNotification copied = fullyPopulatedNotification();
            copied.setReferenceNumber(refNumber("NEW001"));
            copied.setStatus(PlantProductsNotificationStatus.DRAFT);
            when(notificationService.copy(REFERENCE)).thenReturn(copied);

            // When & Then
            mockMvc.perform(post("/plant-products/notifications/{reference-number}/copies", REFERENCE))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                    "http://localhost:8085/plant-products/notifications/" + refNumber("NEW001")))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        void postCopy_shouldReturn400WhenNotCopyable() throws Exception {
            // Given
            when(notificationService.copy(REFERENCE))
                .thenThrow(new PlantProductsBadRequestException("not copyable"));

            // When & Then
            mockMvc.perform(post("/plant-products/notifications/{reference-number}/copies", REFERENCE))
                .andExpect(status().isBadRequest());
        }

        @Test
        void postCopy_shouldReturn404WhenUnknown() throws Exception {
            // Given
            when(notificationService.copy(REFERENCE))
                .thenThrow(new PlantProductsNotFoundException("unknown"));

            // When & Then
            mockMvc.perform(post("/plant-products/notifications/{reference-number}/copies", REFERENCE))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void endpoint_shouldReturn400ForMalformedReferenceNumber() throws Exception {
        // When & Then
        mockMvc.perform(get("/plant-products/notifications/not-a-reference"))
            .andExpect(status().isBadRequest());
    }
}
