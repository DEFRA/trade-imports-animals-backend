package uk.gov.defra.trade.imports.plantproducts.notification;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.mapstruct.factory.Mappers;

@Value
@Builder
public class PlantProductsNotificationContentSnapshot {

    private static final PlantProductsNotificationContentSnapshotMapper MAPPER =
        Mappers.getMapper(PlantProductsNotificationContentSnapshotMapper.class);

    PlantProductsOrigin origin;
    ReasonForImport reasonForImport;
    PlantProductsCommodity commodity;
    PlantProductsAdditionalDetails additionalDetails;
    PlantProductsOperator consignor;
    PlantProductsOperator consignee;
    PlantProductsOperator importer;
    PlantProductsOperator destination;
    PlantProductsOperator packer;
    PlantProductsContact responsiblePerson;
    List<PlantProductsContact> nominatedContacts;
    PlantProductsTransport transport;
    GoodsMovementServices goodsMovementServices;
    Boolean isCuc;
    PlantProductsBilling billing;
    Declaration declaration;

    static PlantProductsNotificationContentSnapshot from(PlantProductsNotification notification) {
        return MAPPER.capture(notification);
    }

    void applyTo(PlantProductsNotification notification) {
        MAPPER.restore(this, notification);
    }
}
