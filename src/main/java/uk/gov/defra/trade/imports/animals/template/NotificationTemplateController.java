package uk.gov.defra.trade.imports.animals.template;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/templates")
@Tag(name = "Notification templates API", description = "A user's saved notification templates")
@Slf4j
@RequiredArgsConstructor
@Validated
public class NotificationTemplateController {

    /** Names the template owner. It must carry the frontend's verified session identity. */
    public static final String HEADER_USER_ID = "User-Id";

    /** A template identifier: the 24 hex characters of a Mongo ObjectId. */
    public static final String TEMPLATE_ID_PATTERN = "^[0-9a-f]{24}$";

    private final NotificationTemplateService notificationTemplateService;

    @GetMapping
    @Operation(summary = "List the user's templates",
        description = "Returns every template the user named by the User-Id header has saved, newest first. Never returns another user's templates.")
    @ApiResponse(responseCode = "200", description = "Templates returned",
        content = @Content(schema = @Schema(implementation = NotificationTemplateListResponse.class)))
    @ApiResponse(responseCode = "400", description = "User-Id header missing or blank", content = @Content)
    @Timed("controller.listTemplates.time")
    public NotificationTemplateListResponse list(
        @RequestHeader(HEADER_USER_ID) @NotBlank String userId) {
        log.debug("GET /templates");
        return notificationTemplateService.listFor(userId);
    }

    @PostMapping
    @Operation(summary = "Start a template",
        description = "Stores a new template for the user named by the User-Id header, held as in progress until it is saved.")
    @ApiResponse(responseCode = "201", description = "Template started",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or User-Id header missing", content = @Content)
    @Timed("controller.startTemplate.time")
    public ResponseEntity<NotificationTemplateResponse> start(
        @RequestHeader(HEADER_USER_ID) @NotBlank String userId,
        @Valid @RequestBody StartTemplateRequest request) {
        log.debug("POST /templates");
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(notificationTemplateService.start(userId, request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a template",
        description = "Returns one template the user owns. Another user's template is reported as not found.")
    @ApiResponse(responseCode = "200", description = "Template returned",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid id or User-Id header missing", content = @Content)
    @ApiResponse(responseCode = "404", description = "Template not found", content = @Content)
    @Timed("controller.getTemplate.time")
    public ResponseEntity<NotificationTemplateResponse> get(
        @RequestHeader(HEADER_USER_ID) @NotBlank String userId,
        @Pattern(regexp = TEMPLATE_ID_PATTERN) @PathVariable String id) {
        log.debug("GET /templates/{}", id);
        return ResponseEntity.ok(notificationTemplateService.get(userId, id));
    }

    @PutMapping("/{id}/details")
    @Operation(summary = "Change a template's type and name",
        description = "Records the journey set and name on a template the user owns.")
    @ApiResponse(responseCode = "200", description = "Details recorded",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or User-Id header missing", content = @Content)
    @ApiResponse(responseCode = "404", description = "Template not found", content = @Content)
    @Timed("controller.updateTemplateDetails.time")
    public ResponseEntity<NotificationTemplateResponse> updateDetails(
        @RequestHeader(HEADER_USER_ID) @NotBlank String userId,
        @Pattern(regexp = TEMPLATE_ID_PATTERN) @PathVariable String id,
        @Valid @RequestBody TemplateDetailsRequest request) {
        log.debug("PUT /templates/{}/details", id);
        return ResponseEntity.ok(notificationTemplateService.updateDetails(userId, id, request));
    }

    @PutMapping("/{id}/fulfilments")
    @Operation(summary = "Replace a template's answers",
        description = "Replaces the answers held on a template the user owns.")
    @ApiResponse(responseCode = "200", description = "Answers replaced",
        content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or User-Id header missing", content = @Content)
    @ApiResponse(responseCode = "404", description = "Template not found", content = @Content)
    @Timed("controller.replaceTemplateFulfilments.time")
    public ResponseEntity<NotificationTemplateResponse> replaceFulfilments(
        @RequestHeader(HEADER_USER_ID) @NotBlank String userId,
        @Pattern(regexp = TEMPLATE_ID_PATTERN) @PathVariable String id,
        @Valid @RequestBody TemplateFulfilmentsRequest request) {
        log.debug("PUT /templates/{}/fulfilments", id);
        return ResponseEntity.ok(
            notificationTemplateService.replaceFulfilments(userId, id, request));
    }
}
