package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.defra.trade.imports.animals.configuration.LocalDateStringConverters.LocalDateToStringConverter;
import uk.gov.defra.trade.imports.animals.configuration.LocalDateStringConverters.StringToLocalDateConverter;

/**
 * A calendar date must store and read back as the same {@code YYYY-MM-DD} string whatever the
 * JVM default zone. Each case runs under a zone either side of UTC, and uses a BST summer date —
 * the one Spring Data's built-in converter stored a day early under {@code Europe/London}
 * (EUDPA-282).
 */
class LocalDateStringConvertersTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 21);
    private static final String STORED = "2026-07-21";

    private TimeZone originalZone;

    @BeforeEach
    void rememberZone() {
        originalZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void write_shouldStoreTheIsoDateString_whateverTheJvmDefaultZone(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        assertThat(new LocalDateToStringConverter().convert(DATE)).isEqualTo(STORED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void read_shouldReturnTheSameCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        assertThat(new StringToLocalDateConverter().convert(STORED)).isEqualTo(DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void roundTrip_shouldPreserveTheCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        String stored = new LocalDateToStringConverter().convert(DATE);

        assertThat(new StringToLocalDateConverter().convert(stored)).isEqualTo(DATE);
    }
}
