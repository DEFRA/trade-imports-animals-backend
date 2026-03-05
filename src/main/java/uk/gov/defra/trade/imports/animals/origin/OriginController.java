package uk.gov.defra.trade.imports.animals.origin;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.animals.domain.Example;

@RestController
@RequestMapping("/origin")
@Tag(name = "Origin API", description = "CRUD operations for the origin in the animals journey")
@Slf4j
@AllArgsConstructor
public class OriginController {

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Submit Origin", description = "Submits an origin to the backend")
    @Timed("controller.postOriginEntity.time")
    public void submit(@Valid @RequestBody Origin entity) {
        log.info("POST /origin - Creating origin with country code: {}", entity.getCountryOfOrigin());
    }
}
