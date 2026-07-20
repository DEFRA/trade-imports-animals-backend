package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.notification.Notification;

@Component
public class GbnAgEventDataMapper {

    public GbnAgEventData toGbnAgEventData(Notification notification) {
        return GbnAgEventData.from(notification);
    }
}
