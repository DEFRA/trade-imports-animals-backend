package uk.gov.defra.trade.imports.plantproducts.notification;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentDto;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentMapper;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentService;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;

@RestController
@RequestMapping("/plant-products/notifications")
@Tag(name = "Plant Products Notification API",
    description = "CRUD and lifecycle operations for CHED-PP plant-products notifications")
@Slf4j
@Validated
public class PlantProductsNotificationController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PlantProductsNotificationService notificationService;
    private final PlantProductsAccompanyingDocumentService accompanyingDocumentService;
    private final PlantProductsNotificationMapper notificationMapper;
    private final PlantProductsAccompanyingDocumentMapper accompanyingDocumentMapper;
    private final String baseUrl;

    public PlantProductsNotificationController(
        PlantProductsNotificationService notificationService,
        PlantProductsAccompanyingDocumentService accompanyingDocumentService,
        PlantProductsNotificationMapper notificationMapper,
        PlantProductsAccompanyingDocumentMapper accompanyingDocumentMapper,
        @Value("${app.base-url:}") String baseUrl) {
        this.notificationService = notificationService;
        this.accompanyingDocumentService = accompanyingDocumentService;
        this.notificationMapper = notificationMapper;
        this.accompanyingDocumentMapper = accompanyingDocumentMapper;
        this.baseUrl = baseUrl;
    }

    @PostMapping
    @Operation(summary = "Create plant-products notification",
        description = "Creates a new DRAFT notification with a server-minted reference number")
    @ApiResponse(responseCode = "201", description = "Notification created",
        content = @Content(schema = @Schema(implementation = PlantProductsNotification.class)))
    @ApiResponse(responseCode = "400", description = "Body carries a reference number", content = @Content)
    @Timed("controller.postPlantProductsNotification.time")
    public ResponseEntity<PlantProductsNotification> create(
        @Valid @RequestBody PlantProductsNotificationDto notificationDto) {
        log.info("POST /plant-products/notifications - Creating notification");
        PlantProductsNotification created = notificationService.create(notificationDto);
        return ResponseEntity.created(notificationLocation(created.getReferenceNumber()))
            .body(created);
    }

    @PutMapping("/{reference-number}")
    @Operation(summary = "Replace plant-products notification",
        description = "Creates or wholly replaces the notification content")
    @ApiResponse(responseCode = "200", description = "Notification replaced",
        content = @Content(schema = @Schema(implementation = PlantProductsNotification.class)))
    @ApiResponse(responseCode = "201", description = "Notification created",
        content = @Content(schema = @Schema(implementation = PlantProductsNotification.class)))
    @ApiResponse(responseCode = "400", description = "Reference mismatch or notification not writable",
        content = @Content)
    @Timed("controller.putPlantProductsNotification.time")
    public ResponseEntity<PlantProductsNotification> replace(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber,
        @Valid @RequestBody PlantProductsNotificationDto notificationDto) {
        log.info("PUT /plant-products/notifications/{} - Replacing notification", referenceNumber);
        PlantProductsNotificationService.ReplaceResult result =
            notificationService.replace(referenceNumber, notificationDto);
        if (result.created()) {
            return ResponseEntity.created(notificationLocation(referenceNumber))
                .body(result.notification());
        }
        return ResponseEntity.ok(result.notification());
    }

    @GetMapping("/{reference-number}")
    @Operation(summary = "Get plant-products notification by reference number",
        description = "Returns a single notification with its accompanying documents")
    @ApiResponse(responseCode = "200", description = "Notification returned",
        content = @Content(schema = @Schema(implementation = PlantProductsNotificationResponse.class)))
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.getPlantProductsNotificationByRef.time")
    public ResponseEntity<PlantProductsNotificationResponse> findByRef(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber) {
        log.debug("GET /plant-products/notifications/{}", referenceNumber);
        PlantProductsNotification notification = notificationService.find(referenceNumber)
            .orElseThrow(() -> new PlantProductsNotFoundException(
                "Cannot find plant-products notification with reference number: " + referenceNumber));
        List<PlantProductsAccompanyingDocumentDto> documents =
            accompanyingDocumentService.list(referenceNumber).stream()
                .map(accompanyingDocumentMapper::toDto)
                .toList();
        return ResponseEntity.ok(notificationMapper.toResponse(notification).toBuilder()
            .accompanyingDocuments(documents)
            .build());
    }

    @GetMapping
    @Operation(summary = "List plant-products notifications",
        description = "Returns a paginated list of notifications. "
            + "Optional sort: arrivalDate,desc (default), arrivalDate,asc, createdAt,desc, createdAt,asc. "
            + "Optional referenceNumber: exact match against a complete notification reference.")
    @ApiResponse(responseCode = "200", description = "Paginated notifications returned",
        content = @Content(schema = @Schema(implementation = PlantProductsNotificationPageResponse.class)))
    @Timed("controller.getAllPlantProductsNotifications.time")
    public PlantProductsNotificationPageResponse findAll(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String referenceNumber) {
        log.debug("GET /plant-products/notifications?page={}&sort={}&referenceNumber={}",
            page, sort, referenceNumber);
        return notificationService.findAll(page, sort, referenceNumber);
    }

    @PutMapping("/{reference-number}/status")
    @Operation(summary = "Change plant-products notification status",
        description = "Carries all lifecycle transitions: submit (DRAFT/AMEND to SUBMITTED), "
            + "start amendment (SUBMITTED to AMEND), cancel amendment (AMEND to SUBMITTED with "
            + "discardChanges=true), and soft delete (any active status to DELETED).")
    @ApiResponse(responseCode = "200", description = "Status changed",
        content = @Content(schema = @Schema(implementation = PlantProductsNotification.class)))
    @ApiResponse(responseCode = "400", description = "Illegal status transition", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.putPlantProductsNotificationStatus.time")
    public ResponseEntity<PlantProductsNotification> changeStatus(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber,
        @Valid @RequestBody StatusChangeRequest request) {
        log.info("PUT /plant-products/notifications/{}/status - target {}",
            referenceNumber, request.status());
        return ResponseEntity.ok(notificationService.changeStatus(referenceNumber, request));
    }

    @PostMapping("/{reference-number}/copies")
    @Operation(summary = "Copy plant-products notification",
        description = "Creates a new DRAFT notification copied from a SUBMITTED or AMEND source. "
            + "Repeating the request with the same Idempotency-Key returns the same copy with the "
            + "same Location.")
    @ApiResponse(responseCode = "201", description = "DRAFT notification copy created or returned",
        content = @Content(schema = @Schema(implementation = PlantProductsNotification.class)))
    @ApiResponse(responseCode = "400",
        description = "Source notification is not in a copyable state or the Idempotency-Key header is blank",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "Source notification not found", content = @Content)
    @Timed("controller.copyPlantProductsNotification.time")
    public ResponseEntity<PlantProductsNotification> copy(
        @Pattern(regexp = PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable("reference-number") String referenceNumber,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        log.info("POST /plant-products/notifications/{}/copies - Copying notification", referenceNumber);
        PlantProductsNotification copy = notificationService.copy(referenceNumber, idempotencyKey);
        return ResponseEntity.created(notificationLocation(copy.getReferenceNumber()))
            .body(copy);
    }

    private URI notificationLocation(String referenceNumber) {
        String trimmedBaseUrl = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        return URI.create(trimmedBaseUrl + "/plant-products/notifications/" + referenceNumber);
    }
}
