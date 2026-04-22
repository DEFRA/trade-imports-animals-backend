package uk.gov.defra.trade.imports.animals.notification;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@Tag(name = "Notification API", description = "CRUD operations for the notification in the animals journey")
@Slf4j
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    @Operation(summary = "Post Origin of the Import", description = "Submits an origin to the backend")
    @Timed("controller.postNotification.time")
    public ResponseEntity<Notification> post(@Valid @RequestBody NotificationDto notificationDto) {
        log.info("POST /notification - Creating Notification with country code: {}",
            notificationDto.getOrigin().getCountryCode());
        return ResponseEntity.ok(notificationService.saveOriginOfImport(notificationDto));
    }

    @GetMapping("/{referenceNumber}")
    @Operation(summary = "Get notification by reference number",
        description = "Returns a single notification with its accompanying documents")
    @Timed("controller.getNotificationByRef.time")
    public ResponseEntity<NotificationResponse> findByRef(@PathVariable String referenceNumber) {
        log.debug("GET /notifications/{} - Fetching notification", referenceNumber);
        return ResponseEntity.ok(notificationService.findByRef(referenceNumber));
    }

    @GetMapping
    @Operation(summary = "List notifications", description = "Returns all import notifications")
    @Timed("controller.getAllNotifications.time")
    public List<Notification> findAll() {
        log.debug("GET /notifications - Fetching all notifications");
        return notificationService.findAll();
    }

    @GetMapping("/reference-numbers")
    @Operation(summary = "List notification reference numbers", description = "Returns all notification reference numbers without loading full documents")
    @Timed("controller.getAllReferenceNumbers.time")
    public List<String> findAllReferenceNumbers() {
        log.debug("GET /notifications/reference-numbers - Fetching all reference numbers");
        return notificationService.findAllReferenceNumbers();
    }

    @DeleteMapping
    @Operation(summary = "Delete notifications", description = "Deletes notifications by reference numbers")
    @Timed("controller.deleteNotifications.time")
    public ResponseEntity<Void> delete(@RequestBody List<String> referenceNumbers,
        @RequestHeader(value = "x-cdp-request-id", required = true) String traceId,
        @RequestHeader(value = "User-Id", required = true) String userId) {
        if (referenceNumbers == null || referenceNumbers.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("DELETE /notifications - Deleting {} notifications", referenceNumbers.size());
        notificationService.deleteByReferenceNumbers(referenceNumbers, traceId, userId);
        return ResponseEntity.noContent().build();
    }
}
