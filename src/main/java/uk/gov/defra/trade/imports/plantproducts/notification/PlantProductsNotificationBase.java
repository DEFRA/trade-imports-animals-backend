package uk.gov.defra.trade.imports.plantproducts.notification;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.mongodb.core.index.Indexed;

@Data
@SuperBuilder
@NoArgsConstructor
public abstract class PlantProductsNotificationBase {

    @Indexed(unique = true, sparse = true)
    private String referenceNumber;

    private String chedType;

    private PlantProductsNotificationStatus status;

    private Ownership ownership;

    private PlantProductsOrigin origin;

    private ReasonForImport reasonForImport;

    private PlantProductsCommodity commodity;

    private PlantProductsAdditionalDetails additionalDetails;

    private PlantProductsOperator consignor;

    private PlantProductsOperator consignee;

    private PlantProductsOperator importer;

    private PlantProductsOperator destination;

    private PlantProductsOperator packer;

    private PlantProductsContact responsiblePerson;

    private List<PlantProductsContact> nominatedContacts;

    private PlantProductsTransport transport;

    private GoodsMovementServices goodsMovementServices;

    private Boolean isCuc;

    private PlantProductsBilling billing;

    private Declaration declaration;

    private LocalDateTime created;

    private LocalDateTime updated;
}
