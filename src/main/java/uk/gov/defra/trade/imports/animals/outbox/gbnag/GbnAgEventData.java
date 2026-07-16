package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gov.defra.trade.imports.animals.notification.Notification;

public record GbnAgEventData(
    @JsonProperty("$model") String model,
    @JsonProperty("$type") String type,
    ExchangedDocument exchangedDocument,
    SpecifiedConsignment specifiedConsignment
) {

    public static final String MODEL_VALUE = "defra/certificate-internal/1";
    public static final String TYPE_VALUE = "gbn-ag";

    public static GbnAgEventData from(Notification notification) {
        if (notification == null) {
            return null;
        }
        return new GbnAgEventData(
            MODEL_VALUE,
            TYPE_VALUE,
            ExchangedDocument.from(notification),
            SpecifiedConsignment.from(notification));
    }
}
