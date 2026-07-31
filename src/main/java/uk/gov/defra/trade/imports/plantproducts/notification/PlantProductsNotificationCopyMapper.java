package uk.gov.defra.trade.imports.plantproducts.notification;

import org.springframework.stereotype.Component;

@Component
public class PlantProductsNotificationCopyMapper {

    public PlantProductsNotification copyFrom(PlantProductsNotification source) {
        PlantProductsNotification copy = new PlantProductsNotification();
        copy.setOrigin(source.getOrigin());
        copy.setReasonForImport(source.getReasonForImport());
        copy.setCommodity(source.getCommodity());
        copy.setAdditionalDetails(source.getAdditionalDetails());
        copy.setConsignor(source.getConsignor());
        copy.setConsignee(source.getConsignee());
        copy.setImporter(source.getImporter());
        copy.setDestination(source.getDestination());
        copy.setPacker(source.getPacker());
        copy.setResponsiblePerson(source.getResponsiblePerson());
        copy.setNominatedContacts(source.getNominatedContacts());
        copy.setTransport(source.getTransport());
        copy.setGoodsMovementServices(source.getGoodsMovementServices());
        copy.setIsCuc(source.getIsCuc());
        copy.setBilling(source.getBilling());
        copy.setOwnership(source.getOwnership());
        return copy;
    }
}
