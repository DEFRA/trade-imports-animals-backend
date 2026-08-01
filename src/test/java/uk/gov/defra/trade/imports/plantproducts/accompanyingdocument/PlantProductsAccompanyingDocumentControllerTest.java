package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsBadRequestException;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsExceptionHandler;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;

@WebMvcTest(PlantProductsAccompanyingDocumentController.class)
@ContextConfiguration(classes = {
    PlantProductsAccompanyingDocumentController.class,
    PlantProductsExceptionHandler.class
})
@TestPropertySource(properties = {
    "admin.secret=test-secret",
    "app.base-url=http://localhost:8085"
})
class PlantProductsAccompanyingDocumentControllerTest {

    private static final String REFERENCE = "GBN-PP-26-ABC001";
    private static final String DOCUMENT_ID = "document-001";
    private static final String BASE_PATH =
        "/plant-products/notifications/{reference-number}/accompanying-documents";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PlantProductsAccompanyingDocumentService documentService;

    @MockitoBean
    private PlantProductsAccompanyingDocumentMapper documentMapper;

    @Nested
    class ListDocuments {

        @Test
        void get_shouldReturnWrappedDocumentsEnvelope() throws Exception {
            // Given
            PlantProductsAccompanyingDocument entity = entity();
            when(documentService.list(REFERENCE)).thenReturn(List.of(entity));
            when(documentMapper.toDto(entity)).thenReturn(dto());

            // When & Then
            mockMvc.perform(get(BASE_PATH, REFERENCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.documents[0].id").value(DOCUMENT_ID))
                .andExpect(jsonPath("$[0]").doesNotExist());
        }

        @Test
        void get_shouldReturn404WhenNotificationIsUnknown() throws Exception {
            // Given
            when(documentService.list(REFERENCE))
                .thenThrow(new PlantProductsNotFoundException("unknown notification"));

            // When & Then
            mockMvc.perform(get(BASE_PATH, REFERENCE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
        }
    }

    @Nested
    class Create {

        @Test
        void post_shouldReturn201LocationAndBody() throws Exception {
            // Given
            PlantProductsAccompanyingDocument entity = entity();
            when(documentService.create(eq(REFERENCE), any())).thenReturn(entity);
            when(documentMapper.toDto(entity)).thenReturn(dto());

            // When & Then
            mockMvc.perform(post(BASE_PATH, REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                    "http://localhost:8085/plant-products/notifications/" + REFERENCE
                        + "/accompanying-documents/" + DOCUMENT_ID))
                .andExpect(jsonPath("$.id").value(DOCUMENT_ID));
        }

        @Test
        void post_shouldReturn400WhenNotificationIsNotWritable() throws Exception {
            // Given
            when(documentService.create(eq(REFERENCE), any()))
                .thenThrow(new PlantProductsBadRequestException("not writable"));

            // When & Then
            mockMvc.perform(post(BASE_PATH, REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("not writable"));
        }

        @Test
        void post_shouldReturn404WhenNotificationIsUnknown() throws Exception {
            // Given
            when(documentService.create(eq(REFERENCE), any()))
                .thenThrow(new PlantProductsNotFoundException("unknown notification"));

            // When & Then
            mockMvc.perform(post(BASE_PATH, REFERENCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto())))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Replace {

        @Test
        void put_shouldReturn200() throws Exception {
            // Given
            PlantProductsAccompanyingDocument entity = entity();
            when(documentService.replace(eq(REFERENCE), eq(DOCUMENT_ID), any())).thenReturn(entity);
            when(documentMapper.toDto(entity)).thenReturn(dto());

            // When & Then
            mockMvc.perform(put(BASE_PATH + "/{document-id}", REFERENCE, DOCUMENT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DOCUMENT_ID));
        }

        @Test
        void put_shouldReturn404WhenDocumentIsNotUnderNotification() throws Exception {
            // Given
            when(documentService.replace(eq(REFERENCE), eq(DOCUMENT_ID), any()))
                .thenThrow(new PlantProductsNotFoundException("document not found"));

            // When & Then
            mockMvc.perform(put(BASE_PATH + "/{document-id}", REFERENCE, DOCUMENT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(dto())))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_shouldReturn204WithEmptyBody() throws Exception {
            // Given
            doNothing().when(documentService).delete(REFERENCE, DOCUMENT_ID);

            // When & Then
            mockMvc.perform(delete(BASE_PATH + "/{document-id}", REFERENCE, DOCUMENT_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        }

        @Test
        void delete_shouldReturn404WhenDocumentIsAbsent() throws Exception {
            // Given
            doThrow(new PlantProductsNotFoundException("document not found"))
                .when(documentService).delete(REFERENCE, DOCUMENT_ID);

            // When & Then
            mockMvc.perform(delete(BASE_PATH + "/{document-id}", REFERENCE, DOCUMENT_ID))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void endpoint_shouldReturn400ForMalformedNotificationReference() throws Exception {
        // When & Then
        mockMvc.perform(get(BASE_PATH, "not-a-reference"))
            .andExpect(status().isBadRequest());
    }

    private static PlantProductsAccompanyingDocumentDto dto() {
        return new PlantProductsAccompanyingDocumentDto(
            DOCUMENT_ID,
            "CHEDPP_PHYTO",
            "PHYTO-BR-001",
            LocalDate.of(2026, 7, 30),
            List.of(DocumentFile.builder().fileId("file-001").filename("certificate.pdf").build()));
    }

    private static PlantProductsAccompanyingDocument entity() {
        return PlantProductsAccompanyingDocument.builder()
            .id(DOCUMENT_ID)
            .notificationReferenceNumber(REFERENCE)
            .documentType("CHEDPP_PHYTO")
            .documentReference("PHYTO-BR-001")
            .issueDate(LocalDate.of(2026, 7, 30))
            .files(List.of())
            .build();
    }
}
