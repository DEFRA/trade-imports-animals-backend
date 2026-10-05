package uk.gov.defra.trade.imports.animals.configuration;

import java.time.LocalDate;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * {@link LocalDate} &harr; {@link String} MongoDB converters, registered as
 * {@code MongoCustomConversions} by {@link MongoConfig#mongoCustomConversions()}.
 *
 * <p>MongoDB has no date-only type. Its {@code Date} is a moment, so storing a calendar date as
 * one means choosing a zone, and Spring Data's built-in JSR-310 converter chooses
 * {@link java.time.ZoneId#systemDefault()} — under {@code Europe/London} during BST,
 * {@code 2026-07-21} lands on {@code 2026-07-20T23:00:00Z}, a calendar day early (EUDPA-282).
 * These converters store the date as its ISO-8601 string, {@code 2026-07-21}, so no zone takes
 * part in either direction and the value reads back as the same date on any host.
 *
 * <p>{@code YYYY-MM-DD} strings sort in date order, so sorts and range queries on these fields
 * work as string comparisons.
 *
 * <p>Registering them applies to every {@code LocalDate} field on every Mongo document. The
 * reader runs only where the target property is a {@code LocalDate}, so {@code String} fields
 * and untyped payloads that happen to hold a date-shaped string are left alone. Moments use
 * {@link java.time.Instant}, which needs no custom converter.
 *
 * @see MongoConfig#mongoCustomConversions()
 */
public final class LocalDateStringConverters {

    private LocalDateStringConverters() {
        // Holder for the converter pair — not instantiable.
    }

    /** Writes a {@code LocalDate} as its ISO-8601 string, for example {@code 2026-07-21}. */
    @WritingConverter
    public enum LocalDateToStringConverter implements Converter<LocalDate, String> {
        INSTANCE;

        @Override
        public String convert(LocalDate source) {
            return source.toString();
        }
    }

    /** Reads a stored {@code YYYY-MM-DD} string back as the calendar date it names. */
    @ReadingConverter
    public enum StringToLocalDateConverter implements Converter<String, LocalDate> {
        INSTANCE;

        @Override
        public LocalDate convert(String source) {
            return LocalDate.parse(source);
        }
    }
}
