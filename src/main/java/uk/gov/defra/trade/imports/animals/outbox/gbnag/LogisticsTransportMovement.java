package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.MeansOfTransport;
import uk.gov.defra.trade.imports.animals.notification.Transport;

public record LogisticsTransportMovement(
    String identifier,
    String urlId,
    Integer modeCode,
    LogisticsTransportMeans usedLogisticsTransportMeans,
    List<ReferencedDocument> transportContractRelatedReferencedDocument,
    List<TransportEvent> arrivalEvent
) {

    public record LogisticsTransportMeans(String name) {

        static LogisticsTransportMeans from(String name) {
            return name != null ? new LogisticsTransportMeans(name) : null;
        }
    }

    @SuppressWarnings("java:S1168")
    static List<LogisticsTransportMovement> from(Transport transport) {
        if (transport == null) {
            return null;
        }
        return List.of(new LogisticsTransportMovement(
            null,
            null,
            modeCode(transport.getMeansOfTransport()),
            LogisticsTransportMeans.from(transport.getTransportIdentification()),
            null,
            TransportEvent.from(transport)));
    }

    private static Integer modeCode(MeansOfTransport meansOfTransport) {
        if (meansOfTransport == null) {
            return null;
        }
        return switch (meansOfTransport) {
            case VESSEL -> 1;
            case RAILWAY -> 2;
            case ROAD_VEHICLE -> 3;
            case AIRPLANE -> 4;
        };
    }
}
