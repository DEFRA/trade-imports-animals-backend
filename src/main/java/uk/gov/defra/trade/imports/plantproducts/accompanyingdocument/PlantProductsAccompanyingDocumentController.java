package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsReferenceNumberGenerator;

@RestController
@RequestMapping("/plant-products/notifications/{reference-number}/accompanying-documents")
@Tag(name = "Plant Products Accompanying Document API",
    description = "CRUD operations for accompanying-document metadata on a plant-products notification")
@Slf4j
@Validated
public class PlantProductsAccompanyingDocumentController {

    private final PlantProductsAccompanyingDocumentService documentService;
    private final PlantProductsAccompanyingDocumentMapper documentMapper;
    private final String baseUrl;

    public PlantProductsAccompanyingDocumentController(
        PlantProductsAccompanyingDocumentService documentService,
        PlantProductsAccompanyingDocumentMapper documentMapper,
        @Value("${app.base-url:}") String baseUrl) {
        this.documentService = documentService;
        this.documentMapper = documentMapper;
        this.baseUrl = baseUrl;
    }

    @GetMapping
    @Operation(summary = "List accompanying documents",
        description = "Returns all accompanying documents for a plant-products notification")
    @ApiResponse(responseCode = "200", description = "Documents returned",
        content = @Content(schema = @Schema(implementation = PlantProductsAccompanyingDocumentListResponse.class)))
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.getPlantProductsAccompanyingDocuments.time")
    public PlantProductsAccompanyingDocumentListResponse list(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber) {
        log.debug("GET /plant-products/notifications/{}/accompanying-documents", referenceNumber);
        List<PlantProductsAccompanyingDocumentDto> documents =
            documentService.list(referenceNumber).stream()
                .map(documentMapper::toDto)
                .toList();
        return new PlantProductsAccompanyingDocumentListResponse(documents);
    }

    @PostMapping
    @Operation(summary = "Add accompanying document",
        description = "Adds an accompanying document to a writable (DRAFT or AMEND) notification")
    @ApiResponse(responseCode = "201", description = "Document created",
        content = @Content(schema = @Schema(implementation = PlantProductsAccompanyingDocumentDto.class)))
    @ApiResponse(responseCode = "400", description = "Notification not writable", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.postPlantProductsAccompanyingDocument.time")
    public ResponseEntity<PlantProductsAccompanyingDocumentDto> create(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber,
        @Valid @RequestBody PlantProductsAccompanyingDocumentDto dto) {
        log.info("POST /plant-products/notifications/{}/accompanying-documents", referenceNumber);
        PlantProductsAccompanyingDocument created = documentService.create(referenceNumber, dto);
        return ResponseEntity.created(documentLocation(referenceNumber, created.getId()))
            .body(documentMapper.toDto(created));
    }

    @PutMapping("/{document-id}")
    @Operation(summary = "Replace accompanying document",
        description = "Wholly replaces an accompanying document's metadata")
    @ApiResponse(responseCode = "200", description = "Document replaced",
        content = @Content(schema = @Schema(implementation = PlantProductsAccompanyingDocumentDto.class)))
    @ApiResponse(responseCode = "400", description = "Notification not writable", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification or document not found", content = @Content)
    @Timed("controller.putPlantProductsAccompanyingDocument.time")
    public ResponseEntity<PlantProductsAccompanyingDocumentDto> replace(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber,
        @PathVariable("document-id") String documentId,
        @Valid @RequestBody PlantProductsAccompanyingDocumentDto dto) {
        log.info("PUT /plant-products/notifications/{}/accompanying-documents/{}",
            referenceNumber, documentId);
        return ResponseEntity.ok(
            documentMapper.toDto(documentService.replace(referenceNumber, documentId, dto)));
    }

    @DeleteMapping("/{document-id}")
    @Operation(summary = "Delete accompanying document",
        description = "Deletes an accompanying document from a writable notification")
    @ApiResponse(responseCode = "204", description = "Document deleted", content = @Content)
    @ApiResponse(responseCode = "400", description = "Notification not writable", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification or document not found", content = @Content)
    @Timed("controller.deletePlantProductsAccompanyingDocument.time")
    public ResponseEntity<Void> delete(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber,
        @PathVariable("document-id") String documentId) {
        log.info("DELETE /plant-products/notifications/{}/accompanying-documents/{}",
            referenceNumber, documentId);
        documentService.delete(referenceNumber, documentId);
        return ResponseEntity.noContent().build();
    }

    private URI documentLocation(String referenceNumber, String documentId) {
        String trimmedBaseUrl = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        return URI.create(trimmedBaseUrl + "/plant-products/notifications/" + referenceNumber
            + "/accompanying-documents/" + documentId);
    }
}
