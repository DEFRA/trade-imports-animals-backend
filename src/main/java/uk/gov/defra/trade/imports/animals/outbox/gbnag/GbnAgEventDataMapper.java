package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;

@Component
public class GbnAgEventDataMapper {

    public GbnAgEventData toGbnAgEventData(NotificationAggregate notification) {
        return GbnAgEventData.from(notification);
    }
}
