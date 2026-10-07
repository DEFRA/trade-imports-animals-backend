package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Transport;

public record TransportEvent(
    String scheduledOccurrenceDateTime,
    String actualOccurrenceDateTime,
    LogisticsLocation occurrenceLogisticsLocation
) {

    @SuppressWarnings("java:S1168")
    static List<TransportEvent> from(Transport transport) {
        if (transport.getArrivalDate() == null) {
            return null;
        }
        return List.of(new TransportEvent(
            toUtcDateTime(transport.getArrivalDate()),
            null,
            null));
    }

    /**
     * PIMS receives an instant, so the arrival date is sent as the start of that day in UTC, for
     * example {@code 2026-07-21T00:00:00Z}. The zone is fixed here rather than taken from the
     * JVM, so the string is the same on any host.
     */
    private static String toUtcDateTime(LocalDate date) {
        return date != null ? date.atStartOfDay(ZoneOffset.UTC).toInstant().toString() : null;
    }
}
