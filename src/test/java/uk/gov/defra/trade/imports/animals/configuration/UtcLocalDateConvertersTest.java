package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Month;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the converter pair is UTC-deterministic — the drift in EUDPA-282 came from
 * {@code ZoneId.systemDefault()} leaking into start-of-day, so every case here runs with the
 * JVM default zone deliberately set to something other than UTC.
 */
class UtcLocalDateConvertersTest {

    /** The ticket's own worked example: 2026-07-21 is UTC midnight at epoch 1784592000000. */
    private static final LocalDate ARRIVAL_DATE = LocalDate.of(2026, Month.JULY, 21);
    private static final long UTC_MIDNIGHT_EPOCH_MILLIS = 1_784_592_000_000L;

    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void localDateToDate_storesUtcStartOfDay_whateverTheJvmDefaultZone(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        Date converted = UtcLocalDateConverters.LocalDateToDateConverter.INSTANCE
            .convert(ARRIVAL_DATE);

        assertThat(converted.getTime()).isEqualTo(UTC_MIDNIGHT_EPOCH_MILLIS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void dateToLocalDate_readsBackTheSameCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        LocalDate converted = UtcLocalDateConverters.DateToLocalDateConverter.INSTANCE
            .convert(new Date(UTC_MIDNIGHT_EPOCH_MILLIS));

        assertThat(converted).isEqualTo(ARRIVAL_DATE);
    }

    @Test
    void localDateToDate_duringBst_doesNotLandOnThePreviousDay() {
        // The exact reproduction from EUDPA-282: Europe/London is UTC+01:00 in July, so the
        // default JSR-310 converter stored 2026-07-20T23:00:00Z (epoch 1784588400000) here.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));

        Date converted = UtcLocalDateConverters.LocalDateToDateConverter.INSTANCE
            .convert(ARRIVAL_DATE);

        assertThat(converted.getTime()).isNotEqualTo(1_784_588_400_000L);
        assertThat(converted.toInstant()).hasToString("2026-07-21T00:00:00Z");
    }

    @Test
    void converters_roundTripEveryDayAcrossADstBoundary() {
        // Europe/London switches to BST on 2026-03-29 — a round trip must be lossless either side.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));

        for (LocalDate date = LocalDate.of(2026, Month.MARCH, 27);
             !date.isAfter(LocalDate.of(2026, Month.MARCH, 31));
             date = date.plusDays(1)) {
            Date stored = UtcLocalDateConverters.LocalDateToDateConverter.INSTANCE.convert(date);

            assertThat(UtcLocalDateConverters.DateToLocalDateConverter.INSTANCE.convert(stored))
                .as("round trip for %s", date)
                .isEqualTo(date);
        }
    }
}
