package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.notification.ActorRequest;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.outbox.Actor;

@RestController
@RequestMapping("/notification-fulfilments")
@Tag(name = "NotificationFulfilments API", description = "CRUD operations for canonical journey fulfilments")
@Slf4j
@Validated
public class NotificationFulfilmentsController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final NotificationFulfilmentsService notificationFulfilmentsService;
    private final String backendBaseUrl;

    public NotificationFulfilmentsController(NotificationFulfilmentsService notificationFulfilmentsService, AppConfig appConfig) {
        this.notificationFulfilmentsService = notificationFulfilmentsService;
        this.backendBaseUrl = appConfig.baseUrl();
    }

    @PostMapping
    @Operation(summary = "Create fulfilment",
        description = "Mints a journey id and creates an empty in-progress fulfilment")
    @ApiResponse(responseCode = "201", description = "NotificationFulfilments created",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @Timed("controller.postFulfilment.time")
    public ResponseEntity<NotificationFulfilments> create() {
        log.info("POST /notification-fulfilments - Creating fulfilment");
        NotificationFulfilments created = notificationFulfilmentsService.create();
        return ResponseEntity.created(buildLocationUri(created.getId())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace fulfilment",
        description = "Creates or wholly replaces a canonical fulfilment")
    @ApiResponse(responseCode = "200", description = "NotificationFulfilments replaced",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "201", description = "NotificationFulfilments created",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "400", description = "Ids do not match or fulfilment is submitted",
        content = @Content)
    @Timed("controller.putFulfilment.time")
    public ResponseEntity<NotificationFulfilments> replace(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @Valid @RequestBody NotificationFulfilmentsDto dto) {
        log.info("PUT /notification-fulfilments/{} - Replacing fulfilment", id);
        NotificationFulfilmentsService.ReplaceResult result = notificationFulfilmentsService.replace(id, dto);
        if (result.created()) {
            return ResponseEntity.created(buildLocationUri(id)).body(result.notificationFulfilments());
        }
        return ResponseEntity.ok(result.notificationFulfilments());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get fulfilment by id",
        description = "Returns the canonical journey fulfilment")
    @ApiResponse(responseCode = "200", description = "NotificationFulfilments returned",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "404", description = "NotificationFulfilments not found", content = @Content)
    @Timed("controller.getFulfilments.time")
    public ResponseEntity<NotificationFulfilments> findById(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.debug("GET /notification-fulfilments/{} - Fetching fulfilment", id);
        return ResponseEntity.ok(notificationFulfilmentsService.findById(id));
    }

    @PostMapping("/{id}/copy")
    @Operation(summary = "Copy fulfilment",
        description = "Creates a new DRAFT fulfilment from an existing fulfilment. "
            + "Repeating the request with the same Idempotency-Key returns the same "
            + "copy with the same Location.")
    @ApiResponse(responseCode = "201", description = "NotificationFulfilments copy created or returned",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "400",
        description = "Source fulfilment is not in a copyable state or header is blank",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "Source fulfilment not found",
        content = @Content)
    @Timed("controller.copyFulfilment.time")
    public ResponseEntity<NotificationFulfilments> copy(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        log.info("POST /notification-fulfilments/{}/copy - Copying fulfilment", id);
        NotificationFulfilments copy = notificationFulfilmentsService.copy(id, idempotencyKey);
        return ResponseEntity.created(buildLocationUri(copy.getId())).body(copy);
    }

    @GetMapping
    @Operation(summary = "List fulfilments",
        description = "Returns fulfilment summaries enriched with notification "
            + "display fields. Optional sort: arrivalDate,desc (default), arrivalDate,asc, "
            + "createdAt,desc, createdAt,asc. Optional referenceNumber: exact match against "
            + "a complete notification reference.")
    @ApiResponse(responseCode = "200", description = "Paginated fulfilment summaries returned",
        content = @Content(schema = @Schema(implementation = NotificationFulfilmentsPageResponse.class)))
    @Timed("controller.getAllFulfilments.time")
    public NotificationFulfilmentsPageResponse findAll(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String referenceNumber) {
        log.debug("GET /notification-fulfilments?page={}&sort={}&referenceNumber={}",
            page, sort, referenceNumber);
        return notificationFulfilmentsService.findAll(page, sort, referenceNumber);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit fulfilment",
        description = "Transitions an in-progress fulfilment to submitted")
    @ApiResponse(responseCode = "200", description = "NotificationFulfilments submitted",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "400", description = "NotificationFulfilments is already submitted",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "NotificationFulfilments not found", content = @Content)
    @Timed("controller.submitFulfilment.time")
    public ResponseEntity<NotificationFulfilments> submit(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @RequestHeader(
            value = NotificationController.HEADER_TRACE_ID,
            required = false,
            defaultValue = "") String traceId,
        @Valid @RequestBody(required = false) ActorRequest actorRequest) {
        log.info("POST /notification-fulfilments/{}/submit - Submitting fulfilment", id);
        Actor actor = actorRequest != null ? actorRequest.toActor() : null;
        return ResponseEntity.ok(notificationFulfilmentsService.submit(id, traceId, actor));
    }

    @PostMapping("/{id}/amend")
    @Operation(summary = "Amend fulfilment",
        description = "Reopens a submitted fulfilment for writes")
    @ApiResponse(responseCode = "200", description = "NotificationFulfilments reopened",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "400", description = "NotificationFulfilments is not submitted",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "NotificationFulfilments not found", content = @Content)
    @Timed("controller.amendFulfilment.time")
    public ResponseEntity<NotificationFulfilments> amend(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @RequestHeader(
            value = NotificationController.HEADER_TRACE_ID,
            required = false,
            defaultValue = "") String traceId,
        @Valid @RequestBody(required = false) ActorRequest actorRequest) {
        log.info("POST /notification-fulfilments/{}/amend - Amending fulfilment", id);
        Actor actor = actorRequest != null ? actorRequest.toActor() : null;
        return ResponseEntity.ok(notificationFulfilmentsService.amend(id, traceId, actor));
    }

    @PostMapping("/{id}/cancel-amend")
    @Operation(summary = "Cancel fulfilment amendment",
        description = "Restores the submitted fulfilment content and transitions from AMEND "
            + "to SUBMITTED")
    @ApiResponse(responseCode = "200", description = "Amendment cancelled",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "400",
        description = "NotificationFulfilments is not in AMEND status or its submitted snapshot is missing",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "NotificationFulfilments not found", content = @Content)
    @Timed("controller.cancelAmendFulfilment.time")
    public ResponseEntity<NotificationFulfilments> cancelAmend(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.info("POST /notification-fulfilments/{}/cancel-amend - Cancelling amendment", id);
        return ResponseEntity.ok(notificationFulfilmentsService.cancelAmend(id));
    }

    @PostMapping("/{id}/soft-delete")
    @Operation(summary = "Soft-delete fulfilment",
        description = "Transitions a DRAFT, SUBMITTED or AMEND fulfilment to terminal DELETED. "
            + "Repeating the request for an already-DELETED fulfilment returns it unchanged.")
    @ApiResponse(responseCode = "200", description = "NotificationFulfilments soft-deleted or already deleted",
        content = @Content(schema = @Schema(implementation = NotificationFulfilments.class)))
    @ApiResponse(responseCode = "404", description = "NotificationFulfilments not found",
        content = @Content)
    @Timed("controller.softDeleteFulfilment.time")
    public ResponseEntity<NotificationFulfilments> softDelete(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.info("POST /notification-fulfilments/{}/soft-delete - Soft deleting fulfilment", id);
        return ResponseEntity.ok(notificationFulfilmentsService.softDelete(id));
    }

    private URI buildLocationUri(String id) {
        String baseUrl = backendBaseUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/notification-fulfilments/" + id);
    }
}
