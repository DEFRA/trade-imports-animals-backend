package uk.gov.defra.trade.imports.animals.configuration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Makes JSON binding of every {@link LocalDate} strict, so a date-only field accepts
 * {@code 2026-07-21} and nothing else.
 *
 * <p>Jackson's {@code LocalDateDeserializer} is lenient by default: handed
 * {@code 2026-07-21T00:00:00Z} or {@code 2026-07-21T00:00:00} it keeps the date part and drops
 * the rest. A caller that sends a moment where a calendar date belongs would then be told 200,
 * and the day it meant would depend on a zone nobody stated. With leniency off the value fails
 * to parse and the caller gets 400.
 *
 * <p>The override is set for the type rather than with {@code @JsonFormat} on each field, so a
 * future {@code LocalDate} field cannot forget it. {@code Instant} binding is untouched.
 *
 * <p>This is a {@code Module} bean because Spring Boot registers every one with the
 * auto-configured {@code ObjectMapper}, including in {@code @WebMvcTest} slices, which do not
 * load {@code @Configuration} classes.
 */
@Component
public class StrictLocalDateModule extends Module {

    @Override
    public String getModuleName() {
        return StrictLocalDateModule.class.getSimpleName();
    }

    @Override
    public Version version() {
        return Version.unknownVersion();
    }

    @Override
    public void setupModule(SetupContext context) {
        context.configOverride(LocalDate.class).setFormat(JsonFormat.Value.forLeniency(false));
    }
}
