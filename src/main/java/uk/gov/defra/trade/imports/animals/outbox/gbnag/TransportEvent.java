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

    static List<TransportEvent> from(Transport transport) {
        if (transport.getArrivalDate() == null) {
            return null;
        }
        return List.of(new TransportEvent(
            toUtcDateTime(transport.getArrivalDate()),
            null,
            null));
    }

    private static String toUtcDateTime(LocalDate date) {
        return date != null ? date.atStartOfDay().toInstant(ZoneOffset.UTC).toString() : null;
    }
}
