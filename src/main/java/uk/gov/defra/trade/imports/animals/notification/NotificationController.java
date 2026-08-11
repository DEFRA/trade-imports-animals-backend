package uk.gov.defra.trade.imports.animals.notification;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.animals.outbox.Actor;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxReplayService;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

@RestController
@RequestMapping("/notifications")
@Tag(name = "Notification API", description = "CRUD operations for the notification in the animals journey")
@Slf4j
@RequiredArgsConstructor
@Validated
public class NotificationController {

    public static final String HEADER_TRACE_ID = "x-cdp-request-id";
    public static final String HEADER_USER_ID = "User-Id";

    private final NotificationService notificationService;
    private final OutboxService outboxService;
    private final OutboxReplayService outboxReplayService;

    @PostMapping
    @Operation(summary = "Post Origin of the Import", description = "Submits an origin to the backend")
    @Timed("controller.postNotification.time")
    public ResponseEntity<Notification> post(@Valid @RequestBody NotificationDto notificationDto) {
        log.info("POST /notifications - countryCode={}",
            notificationDto.getOrigin() != null ? notificationDto.getOrigin().getCountryCode() : null);
        return ResponseEntity.ok(notificationService.saveOriginOfImport(notificationDto));
    }

    @PutMapping("/{referenceNumber}")
    @Operation(summary = "Replace notification content",
        description = "Replaces the merged notification content (notification-shape fields + opaque fulfilments payload) at the given reference. Requires DRAFT or AMEND status.")
    @ApiResponse(responseCode = "200", description = "Notification content replaced",
        content = @Content(schema = @Schema(implementation = Notification.class)))
    @ApiResponse(responseCode = "400", description = "Notification not in a replaceable state", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.replaceNotification.time")
    public ResponseEntity<Notification> replace(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber,
        @Valid @RequestBody NotificationDto notificationDto) {
        log.info("PUT /notifications/{} - Replacing notification content", referenceNumber);
        return ResponseEntity.ok(notificationService.replace(referenceNumber, notificationDto));
    }

    @PostMapping("/{referenceNumber}/copy")
    @Operation(summary = "Copy notification", description = "Creates a new DRAFT notification copied from an existing one")
    @ApiResponse(responseCode = "200", description = "New DRAFT notification created",
        content = @Content(schema = @Schema(implementation = Notification.class)))
    @ApiResponse(responseCode = "400", description = "Source notification is not in a copyable status", content = @Content)
    @ApiResponse(responseCode = "404", description = "Source notification not found", content = @Content)
    @Timed("controller.copyNotification.time")
    public ResponseEntity<Notification> copy(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber) {
        log.info("POST /notifications/{}/copy - Copying notification", referenceNumber);
        return ResponseEntity.ok(notificationService.copyNotification(referenceNumber));
    }

    @PostMapping("/{referenceNumber}/submit")
    @Operation(summary = "Submit notification",
        description = "Transitions notification status to SUBMITTED. Accepts DRAFT or AMEND as the source state.")
    @ApiResponse(responseCode = "200", description = "Notification submitted",
        content = @Content(schema = @Schema(implementation = Notification.class)))
    @ApiResponse(responseCode = "400", description = "Notification not in a submittable state", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @ApiResponse(responseCode = "500", description = "Submission failed", content = @Content)
    @Timed("controller.submitNotification.time")
    public ResponseEntity<Notification> submit(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber,
        @RequestHeader(value = HEADER_TRACE_ID, required = false, defaultValue = "") String traceId,
        @RequestBody(required = false) ActorRequest actorRequest) {
        log.info("POST /notifications/{}/submit - Submitting notification", referenceNumber);
        Actor actor = actorRequest != null ? actorRequest.toActor() : null;
        return ResponseEntity.ok(notificationService.submitNotification(referenceNumber, traceId, actor));
    }

    @PostMapping("/{referenceNumber}/amend")
    @Operation(summary = "Amend notification",
        description = "Transitions notification status from SUBMITTED to AMEND. Emits an outbox event.")
    @ApiResponse(responseCode = "200", description = "Notification moved to AMEND",
        content = @Content(schema = @Schema(implementation = Notification.class)))
    @ApiResponse(responseCode = "400", description = "Notification not in an amendable state", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @ApiResponse(responseCode = "500", description = "Amend failed", content = @Content)
    @Timed("controller.amendNotification.time")
    public ResponseEntity<Notification> amend(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber,
        @RequestHeader(value = HEADER_TRACE_ID, required = false, defaultValue = "") String traceId,
        @RequestBody(required = false) ActorRequest actorRequest) {
        log.info("POST /notifications/{}/amend - Amending notification", referenceNumber);
        Actor actor = actorRequest != null ? actorRequest.toActor() : null;
        return ResponseEntity.ok(notificationService.amendNotification(referenceNumber, traceId, actor));
    }

    @PostMapping("/{referenceNumber}/cancel-amend")
    @Operation(summary = "Cancel notification amendment",
        description = "Restores the submitted notification content and transitions status from AMEND to SUBMITTED.")
    @ApiResponse(responseCode = "200", description = "Amendment cancelled",
        content = @Content(schema = @Schema(implementation = Notification.class)))
    @ApiResponse(responseCode = "400", description = "Notification not in AMEND status or baseline missing", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.cancelAmendNotification.time")
    public ResponseEntity<Notification> cancelAmend(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber) {
        log.info("POST /notifications/{}/cancel-amend - Cancelling amendment", referenceNumber);
        return ResponseEntity.ok(notificationService.cancelAmendNotification(referenceNumber));
    }

    @GetMapping
    @Operation(summary = "List notifications",
        description = "Returns a paginated list of import notifications. "
            + "Optional sort: arrivalDate,desc (default), arrivalDate,asc, createdAt,desc, createdAt,asc. "
            + "Optional referenceNumber: exact match against a complete notification reference.")
    @ApiResponse(responseCode = "200", description = "Paginated notifications returned",
        content = @Content(schema = @Schema(implementation = NotificationPageResponse.class)))
    @Timed("controller.getAllNotifications.time")
    public NotificationPageResponse findAll(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String referenceNumber) {
        log.debug("GET /notifications?page={}&sort={}&referenceNumber={}", page, sort, referenceNumber);
        return notificationService.findAll(page, sort, referenceNumber);
    }

    @GetMapping("/reference-numbers")
    @Operation(summary = "List notification reference numbers",
        description = "Returns a paginated list of notification reference numbers without loading full documents")
    @ApiResponse(responseCode = "200", description = "Paginated reference numbers returned",
        content = @Content(schema = @Schema(implementation = ReferenceNumberPageResponse.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @Timed("controller.getAllReferenceNumbers.time")
    public ReferenceNumberPageResponse findAllReferenceNumbers(
        @RequestParam(defaultValue = "0") @Min(0) int page) {
        log.debug("GET /notifications/reference-numbers?page={}", page);
        return notificationService.findAllReferenceNumbers(page);
    }

    @GetMapping("/{referenceNumber}/outbox-events")
    @Operation(summary = "Get outbox events for a notification",
        description = "Returns all outbox events for a notification reference number in chronological write order (aggregateVersion ascending)")
    @ApiResponse(responseCode = "200", description = "Events returned", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @Timed("controller.getOutboxEvents.time")
    public List<OutboxEvent> getOutboxEvents(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber) {
        log.debug("GET /notifications/{}/outbox-events - Fetching outbox events", referenceNumber);
        return outboxService.findByReferenceNumber(referenceNumber);
    }

    @PostMapping("/{referenceNumber}/replay")
    @Operation(summary = "Replay outbox events",
        description = "Re-publishes all outbox events for a notification to SNS FIFO in aggregate version order. "
            + "The outbox is read-only — no data is mutated. Each event is re-published with the same "
            + "MessageGroupId (aggregateId) and MessageDeduplicationId (eventId) as the original publish.")
    @ApiResponse(responseCode = "200", description = "Replay complete",
        content = @Content(schema = @Schema(implementation = ReplayResponse.class)))
    @ApiResponse(responseCode = "404", description = "No outbox events found for this notification", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @Timed("controller.replayOutboxEvents.time")
    public ResponseEntity<ReplayResponse> replay(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber,
        @RequestHeader(value = HEADER_TRACE_ID, required = false, defaultValue = "") String traceId,
        @RequestHeader(value = HEADER_USER_ID, required = true) String userId) {
        log.info("POST /notifications/{}/replay - userId={}", referenceNumber, userId);
        int count = outboxReplayService.replay(referenceNumber, new AuditContext(traceId, userId));
        return ResponseEntity.ok(new ReplayResponse(count));
    }

    @PostMapping("/{referenceNumber}/soft-delete")
    @Operation(summary = "Soft-delete notification",
        description = "Transitions notification status to DELETED (soft delete). Only DRAFT and SUBMITTED notifications can be deleted.")
    @ApiResponse(responseCode = "200", description = "Notification soft-deleted",
        content = @Content(schema = @Schema(implementation = Notification.class)))
    @ApiResponse(responseCode = "400", description = "Notification not in a deletable state", content = @Content)
    @ApiResponse(responseCode = "401", description = "Unauthorised", content = @Content)
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.softDeleteNotification.time")
    public ResponseEntity<Notification> softDelete(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN) @PathVariable String referenceNumber) {
        log.info("POST /notifications/{}/soft-delete - Soft deleting notification", referenceNumber);
        return ResponseEntity.ok(notificationService.softDeleteNotification(referenceNumber));
    }

    @DeleteMapping
    @Operation(summary = "Delete notifications", description = "Deletes notifications by reference numbers")
    @Timed("controller.deleteNotifications.time")
    public ResponseEntity<Void> delete(@RequestBody List<String> referenceNumbers,
        @RequestHeader(value = NotificationController.HEADER_TRACE_ID, required = true) String traceId,
        @RequestHeader(value = NotificationController.HEADER_USER_ID, required = true) String userId) {
        if (referenceNumbers == null || referenceNumbers.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("DELETE /notifications - Deleting {} notifications", referenceNumbers.size());
        notificationService.deleteByReferenceNumbers(referenceNumbers, new AuditContext(traceId, userId));
        return ResponseEntity.noContent().build();
    }
}
