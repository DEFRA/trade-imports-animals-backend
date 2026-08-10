package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;

/**
 * Slim survivor of the pre-EUDPA-323 fulfilments controller. All writes and lifecycle
 * transitions moved to {@code /notifications} under {@code NotificationController} when the
 * aggregates folded (EUDPA-312 option 2a); this class exists solely to serve the frontend's
 * journey-rehydrate read at {@code GET /notification-fulfilments/{id}} backed by the merged
 * {@code notification} collection via {@link NotificationFulfilmentsView}.
 */
@RestController
@RequestMapping("/notification-fulfilments")
@RequiredArgsConstructor
@Tag(name = "NotificationFulfilments API", description = "Fulfilment-view read of the merged notification aggregate")
@Slf4j
@Validated
public class NotificationFulfilmentsController {

    private final NotificationRepository notificationRepository;

    @GetMapping("/{id}")
    @Operation(summary = "Get fulfilment view by reference number",
        description = "Returns the fulfilment-view projection (id, status, dates, opaque fulfilments payload) of the merged notification at the given reference. The URL path {id} is the reference number by convention.")
    @ApiResponse(responseCode = "200", description = "NotificationFulfilments view returned",
        content = @Content(schema = @Schema(implementation = NotificationFulfilmentsView.class)))
    @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
    @Timed("controller.getFulfilments.time")
    public ResponseEntity<NotificationFulfilmentsView> findById(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.debug("GET /notification-fulfilments/{} - Fetching fulfilment view", id);
        return ResponseEntity.ok(notificationRepository.findFulfilmentsViewByReferenceNumber(id)
            .orElseThrow(() -> new NotFoundException("Cannot find notification with reference number: " + id)));
    }
}
