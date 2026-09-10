package uk.gov.defra.trade.imports.animals.configuration;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * UTC-scoped {@link LocalDate} &harr; {@link Date} MongoDB converters, registered as
 * {@code MongoCustomConversions} by {@link MongoConfig#mongoCustomConversions()}.
 *
 * <p>MongoDB has no date-only type, so a {@code LocalDate} field must be stored as a
 * {@code Date} (a UTC instant). Spring Data's built-in JSR-310 converter resolves start-of-day
 * using {@link java.time.ZoneId#systemDefault()}, so on a non-UTC JVM the stored instant drifts
 * by the zone offset — under {@code Europe/London} during BST, {@code 2026-07-21} lands on
 * {@code 2026-07-20T23:00:00Z}, an hour and a calendar day early (EUDPA-282). These converters
 * pin both directions to {@link ZoneOffset#UTC} so the stored instant is UTC start-of-day for
 * the given calendar date whatever the host timezone.
 *
 * <p>Registering them applies to every {@code LocalDate} field on every Mongo document, not just
 * the field that surfaced the bug — the guarantee is structural rather than per-field, so a
 * future {@code LocalDate} field cannot reintroduce the drift. Fields that need a timestamp
 * rather than a date should use {@link java.time.Instant} directly, as
 * {@code AccompanyingDocument.dateOfIssue} does; {@code Instant} needs no custom converter.
 *
 * @see MongoConfig#mongoCustomConversions()
 */
public final class UtcLocalDateConverters {

    private UtcLocalDateConverters() {
        // Holder for the converter pair — not instantiable.
    }

    /** Writes a {@code LocalDate} as UTC start-of-day, ignoring the JVM's default timezone. */
    @WritingConverter
    public enum LocalDateToDateConverter implements Converter<LocalDate, Date> {
        INSTANCE;

        @Override
        public Date convert(LocalDate source) {
            return Date.from(source.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }

    /** Reads a stored instant back as the calendar date it represents in UTC. */
    @ReadingConverter
    public enum DateToLocalDateConverter implements Converter<Date, LocalDate> {
        INSTANCE;

        @Override
        public LocalDate convert(Date source) {
            return source.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
    }
}
