package uk.gov.defra.trade.imports.animals.notification;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notification")
@Tag(name = "Notification API", description = "CRUD operations for the notification in the animals journey")
@Slf4j
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Post Origin of the Import", description = "Submits an origin to the backend")
    @Timed("controller.postNotification.time")
    public String post(@Valid @RequestBody Notification notification) {
        log.info("POST /notification - Creating Notification with country code: {}", notification.getOrigin().getCountryOfOrigin());
        return notificationService.saveOriginOfImport(notification);
    }

}
