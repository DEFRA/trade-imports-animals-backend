package uk.gov.defra.trade.imports.animals.fulfilment;

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
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;

@RestController
@RequestMapping("/fulfilments")
@Tag(name = "Fulfilment API", description = "CRUD operations for canonical journey fulfilments")
@Slf4j
@Validated
public class FulfilmentController {

    private final FulfilmentService fulfilmentService;
    private final String backendBaseUrl;

    public FulfilmentController(FulfilmentService fulfilmentService, AppConfig appConfig) {
        this.fulfilmentService = fulfilmentService;
        this.backendBaseUrl = appConfig.baseUrl();
    }

    @PostMapping
    @Operation(summary = "Create fulfilment",
        description = "Mints a journey id and creates an empty in-progress fulfilment")
    @ApiResponse(responseCode = "201", description = "Fulfilment created",
        content = @Content(schema = @Schema(implementation = Fulfilment.class)))
    @Timed("controller.postFulfilment.time")
    public ResponseEntity<Fulfilment> create() {
        log.info("POST /fulfilments - Creating fulfilment");
        Fulfilment created = fulfilmentService.create();
        return ResponseEntity.created(buildLocationUri(created.getId())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace fulfilment",
        description = "Creates or wholly replaces a canonical fulfilment")
    @ApiResponse(responseCode = "200", description = "Fulfilment replaced",
        content = @Content(schema = @Schema(implementation = Fulfilment.class)))
    @ApiResponse(responseCode = "201", description = "Fulfilment created",
        content = @Content(schema = @Schema(implementation = Fulfilment.class)))
    @ApiResponse(responseCode = "400", description = "Ids do not match or fulfilment is submitted",
        content = @Content)
    @Timed("controller.putFulfilment.time")
    public ResponseEntity<Fulfilment> replace(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id,
        @Valid @RequestBody FulfilmentDto dto) {
        log.info("PUT /fulfilments/{} - Replacing fulfilment", id);
        FulfilmentService.ReplaceResult result = fulfilmentService.replace(id, dto);
        if (result.created()) {
            return ResponseEntity.created(buildLocationUri(id)).body(result.fulfilment());
        }
        return ResponseEntity.ok(result.fulfilment());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get fulfilment by id",
        description = "Returns the canonical journey fulfilment")
    @ApiResponse(responseCode = "200", description = "Fulfilment returned",
        content = @Content(schema = @Schema(implementation = Fulfilment.class)))
    @ApiResponse(responseCode = "404", description = "Fulfilment not found", content = @Content)
    @Timed("controller.getFulfilment.time")
    public ResponseEntity<Fulfilment> findById(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.debug("GET /fulfilments/{} - Fetching fulfilment", id);
        return ResponseEntity.ok(fulfilmentService.findById(id));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit fulfilment",
        description = "Transitions an in-progress fulfilment to submitted")
    @ApiResponse(responseCode = "200", description = "Fulfilment submitted",
        content = @Content(schema = @Schema(implementation = Fulfilment.class)))
    @ApiResponse(responseCode = "400", description = "Fulfilment is already submitted",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "Fulfilment not found", content = @Content)
    @Timed("controller.submitFulfilment.time")
    public ResponseEntity<Fulfilment> submit(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.info("POST /fulfilments/{}/submit - Submitting fulfilment", id);
        return ResponseEntity.ok(fulfilmentService.submit(id));
    }

    @PostMapping("/{id}/amend")
    @Operation(summary = "Amend fulfilment",
        description = "Reopens a submitted fulfilment for writes")
    @ApiResponse(responseCode = "200", description = "Fulfilment reopened",
        content = @Content(schema = @Schema(implementation = Fulfilment.class)))
    @ApiResponse(responseCode = "400", description = "Fulfilment is not submitted",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "Fulfilment not found", content = @Content)
    @Timed("controller.amendFulfilment.time")
    public ResponseEntity<Fulfilment> amend(
        @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
        @PathVariable String id) {
        log.info("POST /fulfilments/{}/amend - Amending fulfilment", id);
        return ResponseEntity.ok(fulfilmentService.amend(id));
    }

    private URI buildLocationUri(String id) {
        String baseUrl = backendBaseUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/fulfilments/" + id);
    }
}
