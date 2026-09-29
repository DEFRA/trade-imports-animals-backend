package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.convert.Jsr310Converters;

/**
 * EUDPA-565 spike — what actually happens to a persisted {@code LocalDateTime} when it is written
 * before a daylight-saving switch and read back after one?
 *
 * <p>{@code NotificationAggregate.created}, {@code updated}, {@code submittedAt}, {@code expireAt}
 * and {@code Audit.timestamp} are {@code LocalDateTime} today. MongoDB has one date type — a UTC
 * instant — so Spring Data resolves them through the JSR-310 converter pair driven here, which
 * uses {@link ZoneId#systemDefault()} in both directions. EUDPA-282 identified that same pair as
 * the source of the {@code transport.arrivalDate} drift; {@link UtcLocalDateConverters} replaced it
 * for {@code LocalDate} only, so these five fields still go through the stock pair.
 *
 * <p>Both cases store a timestamp in February 2026 for a moment in April 2026 and read it back in
 * May 2026, with the JVM default zone set to a zone that changes offset in between — the condition
 * the ticket describes as latent, reachable via a bare {@code mvn spring-boot:run} on a developer
 * machine or an environment where {@code TZ=UTC} is dropped.
 *
 * <p><b>Finding: crossing a DST boundary between write and read is not the failure mode.</b> The
 * round trip is lossless in both zones, because {@code atZone()} resolves the offset against the
 * value's own date rather than against "now" — so when the value is read makes no difference. The
 * failure mode is a change of <em>zone</em> between write and read, which the third block of each
 * test provokes. That is the gap {@code Instant} closes, and it is the reason the ticket's
 * acceptance criteria call for a raw-BSON assertion rather than a repository round trip: a round
 * trip decodes with the same zone it encoded with and passes either way.
 *
 * @see UtcLocalDateConvertersTest
 */
class PersistedTimestampZoneSpikeTest {

    /** The notification is written here: GMT in London, EET in Bucharest. Both standard time. */
    private static final Clock WRITING_IN_FEBRUARY =
        Clock.fixed(Instant.parse("2026-02-10T09:00:00Z"), ZoneOffset.UTC);

    /** It is read back here: BST in London, EEST in Bucharest. Both summer time. */
    private static final Clock READING_IN_MAY =
        Clock.fixed(Instant.parse("2026-05-20T09:00:00Z"), ZoneOffset.UTC);

    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void localDateTime_writtenInFebruaryOnABstJvm_readsBackUnchangedInMay() {
        // Given — a backend JVM on Europe/London, and a timestamp of 4 April 2026 23:30 written
        // in February. The EU switch to summer time is 29 March 2026, so the value itself falls
        // in BST while the JVM is still on GMT on the day it is written.
        runOn("Europe/London");
        LocalDateTime timestamp = LocalDateTime.of(2026, Month.APRIL, 4, 23, 30);

        // When — the stock Spring Data pair these five fields still use.
        Date stored = write(timestamp);

        // Then — BST (UTC+01:00) was applied, not February's GMT: 23:30 local is 22:30Z, still on
        // the 4th. The offset came from the value's own date, which is why the write moment is
        // irrelevant even though the two clocks below straddle the switch.
        assertThat(stored.toInstant()).hasToString("2026-04-04T22:30:00Z");
        assertThat(offsetAt("Europe/London", WRITING_IN_FEBRUARY)).isEqualTo(ZoneOffset.UTC);
        assertThat(offsetAt("Europe/London", READING_IN_MAY)).isEqualTo(ZoneOffset.ofHours(1));

        // And — reading it back in May, on the far side of the switch, returns it unchanged. The
        // read converter takes only the stored Date, so the reading moment cannot reach it either.
        assertThat(read(stored)).isEqualTo(timestamp);
        assertThat(read(stored)).hasToString("2026-04-04T23:30");

        // And — the real EUDPA-565 exposure: the same stored value read on a JVM whose default
        // zone is UTC comes back an hour early. Nothing about the document changed — only the
        // process that read it. This is what "timezone-dependent at the code level" means.
        runOn("UTC");
        assertThat(read(stored)).isEqualTo(LocalDateTime.of(2026, Month.APRIL, 4, 22, 30));

        // Whereas the Instant the ticket proposes storing instead needs no zone to be read, so a
        // zone change has nothing to corrupt: it is the same moment in either process.
        assertThat(stored.toInstant()).hasToString("2026-04-04T22:30:00Z");
    }

    @Test
    void localDateTime_writtenInFebruaryOnARomanianJvm_readsBackUnchangedInMay() {
        // Given — a developer machine on Europe/Bucharest running the service directly, and a
        // timestamp of 16 April 2026 23:30 written in February. Romania keeps the same EU switch
        // date, so the value falls in EEST while the JVM is still on EET when it is written.
        runOn("Europe/Bucharest");
        LocalDateTime timestamp = LocalDateTime.of(2026, Month.APRIL, 16, 23, 30);

        // When
        Date stored = write(timestamp);

        // Then — EEST (UTC+03:00) was applied, not February's EET: 23:30 local is 20:30Z, still
        // on the 16th.
        assertThat(stored.toInstant()).hasToString("2026-04-16T20:30:00Z");
        assertThat(offsetAt("Europe/Bucharest", WRITING_IN_FEBRUARY)).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(offsetAt("Europe/Bucharest", READING_IN_MAY)).isEqualTo(ZoneOffset.ofHours(3));

        // And — reading it back in May returns it unchanged, as above.
        assertThat(read(stored)).isEqualTo(timestamp);
        assertThat(read(stored)).hasToString("2026-04-16T23:30");

        // And — the same document read back on a UTC JVM loses three hours, and with it the
        // calendar day would shift for any value before 03:00.
        runOn("UTC");
        assertThat(read(stored)).isEqualTo(LocalDateTime.of(2026, Month.APRIL, 16, 20, 30));

        // The Instant is unaffected by which process reads it.
        assertThat(stored.toInstant()).hasToString("2026-04-16T20:30:00Z");
    }

    /** Pins the JVM default zone, which is what both stock converters resolve against. */
    private static void runOn(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
    }

    /** Spring Data's writing converter for a {@code LocalDateTime} field: {@code atZone(systemDefault())}. */
    private static Date write(LocalDateTime value) {
        return Jsr310Converters.LocalDateTimeToDateConverter.INSTANCE.convert(value);
    }

    /** Spring Data's reading converter: {@code ofInstant(source.toInstant(), systemDefault())}. */
    private static LocalDateTime read(Date value) {
        return Jsr310Converters.DateToLocalDateTimeConverter.INSTANCE.convert(value);
    }

    /** The zone's offset as at a given moment — shown only to prove the switch was straddled. */
    private static ZoneOffset offsetAt(String zoneId, Clock moment) {
        return ZoneId.of(zoneId).getRules().getOffset(moment.instant());
    }
}
