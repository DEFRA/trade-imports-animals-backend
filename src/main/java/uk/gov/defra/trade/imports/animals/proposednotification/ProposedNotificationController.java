package uk.gov.defra.trade.imports.animals.proposednotification;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.ownership.Owner;
import uk.gov.defra.trade.imports.animals.ownership.OwnerHeaders;

@RestController
@RequestMapping("/proposed-notifications")
@Tag(name = "Proposed Notification API",
    description = "CRUD operations for full-fat proposed notification projections")
@Slf4j
@Validated
public class ProposedNotificationController {

    private final ProposedNotificationService proposedNotificationService;
    private final String backendBaseUrl;

    public ProposedNotificationController(
        ProposedNotificationService proposedNotificationService, AppConfig appConfig) {
        this.proposedNotificationService = proposedNotificationService;
        this.backendBaseUrl = appConfig.baseUrl();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace proposed notification",
        description = "Creates or wholly replaces an opaque proposed notification projection")
    @ApiResponse(responseCode = "200", description = "Proposed notification replaced")
    @ApiResponse(responseCode = "201", description = "Proposed notification created")
    @ApiResponse(responseCode = "400", description = "Reference numbers do not match",
        content = @Content)
    @Timed("controller.putProposedNotification.time")
    public ResponseEntity<Document> replace(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @RequestBody Document body,
        @RequestHeader(OwnerHeaders.OWNER_ID) @NotBlank String ownerId,
        @RequestHeader(OwnerHeaders.OWNER_ORGANISATION) String ownerOrganisation) {
        log.info("PUT /proposed-notifications/{} - Replacing proposed notification", id);
        ProposedNotificationService.ReplaceResult result =
            proposedNotificationService.replace(
                id, body, owner(ownerId, ownerOrganisation));
        if (result.created()) {
            return ResponseEntity.created(buildLocationUri(id)).body(result.body());
        }
        return ResponseEntity.ok(result.body());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get proposed notification by id",
        description = "Returns the opaque full-fat proposed notification projection")
    @ApiResponse(responseCode = "200", description = "Proposed notification returned")
    @ApiResponse(responseCode = "404", description = "Proposed notification not found",
        content = @Content)
    @Timed("controller.getProposedNotification.time")
    public ResponseEntity<Document> findById(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @RequestHeader(OwnerHeaders.OWNER_ID) @NotBlank String ownerId,
        @RequestHeader(OwnerHeaders.OWNER_ORGANISATION) String ownerOrganisation) {
        log.debug("GET /proposed-notifications/{} - Fetching proposed notification", id);
        return ResponseEntity.ok(
            proposedNotificationService.findById(
                id, owner(ownerId, ownerOrganisation)));
    }

    private Owner owner(String ownerId, String ownerOrganisation) {
        return OwnerHeaders.toOwner(ownerId, ownerOrganisation);
    }

    private URI buildLocationUri(String id) {
        String baseUrl = backendBaseUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/proposed-notifications/" + id);
    }
}
