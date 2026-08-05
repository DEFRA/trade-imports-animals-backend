package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class PlantProductsNotificationCopyMapper {

    public PlantProductsNotification copyFrom(PlantProductsNotification source) {
        PlantProductsNotification copy = new PlantProductsNotification();
        PlantProductsNotificationContentSnapshot.from(source).applyTo(copy);
        copy.setDeclaration(null);
        copy.setStatus(PlantProductsNotificationStatus.DRAFT);
        copy.setCreated(LocalDateTime.now());
        copy.setUpdated(LocalDateTime.now());
        if (source.getOwnership() != null) {
            copy.setOwnership(source.getOwnership().toBuilder().build());
        }
        return copy;
    }
}
