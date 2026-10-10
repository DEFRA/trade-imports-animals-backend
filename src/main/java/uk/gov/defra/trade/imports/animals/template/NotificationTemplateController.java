package uk.gov.defra.trade.imports.animals.template;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
}
