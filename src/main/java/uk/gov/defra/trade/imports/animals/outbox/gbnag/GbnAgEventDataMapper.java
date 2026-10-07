package uk.gov.defra.trade.imports.animals.outbox.gbnag;

import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;

@Component
public class GbnAgEventDataMapper {

    public GbnAgEventData toGbnAgEventData(
        NotificationAggregate notificationAggregate,
        Integer versionId,
        List<AccompanyingDocument> accompanyingDocuments) {
        return GbnAgEventData.from(notificationAggregate, versionId, accompanyingDocuments);
    }
}
