package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.time.Instant;
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
     * The arrival date arrives as UTC midnight — the frontend labels the user's chosen calendar
     * day as UTC rather than converting it from a local zone, so this emits the same string the
     * previous {@code atStartOfDay().toInstant(ZoneOffset.UTC)} produced.
     */
    private static String toUtcDateTime(Instant date) {
        return date != null ? date.toString() : null;
    }
}
