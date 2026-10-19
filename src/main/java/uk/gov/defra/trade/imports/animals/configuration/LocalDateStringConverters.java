package uk.gov.defra.trade.imports.animals.configuration;

import java.time.LocalDate;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * MongoDB converters that store every {@link LocalDate} field as its ISO-8601 {@link String},
 * for example {@code 2026-07-21}, and read it back as the same date in any JVM zone.
 *
 * @see MongoConfig#mongoCustomConversions()
 */
public final class LocalDateStringConverters {

    private LocalDateStringConverters() {
    }

    /** Writes a {@code LocalDate} as its ISO-8601 string, for example {@code 2026-07-21}. */
    @WritingConverter
    public static class LocalDateToStringConverter implements Converter<LocalDate, String> {

        @Override
        public String convert(LocalDate source) {
            return source.toString();
        }
    }

    /** Reads a stored {@code YYYY-MM-DD} string back as the calendar date it names. */
    @ReadingConverter
    public static class StringToLocalDateConverter implements Converter<String, LocalDate> {

        @Override
        public LocalDate convert(String source) {
            return LocalDate.parse(source);
        }
    }
}
