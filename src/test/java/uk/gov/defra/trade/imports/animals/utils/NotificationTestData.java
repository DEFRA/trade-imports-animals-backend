package uk.gov.defra.trade.imports.animals.utils;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.Consignor;
import uk.gov.defra.trade.imports.animals.notification.Species;

public final class NotificationTestData {

    private NotificationTestData() {}

    public static Species species() {
        return Species.builder()
            .value("BOV")
            .text("Bovine")
            .noOfAnimals(10)
            .noOfPackages(5)
            .earTag("UK01234567890")
            .passport("UK0123456700999")
            .build();
    }
    
    public static List<Consignor> consignors() {
        return List.of(
            Consignor.builder()
                .name("Astra Rosales")
                .address(Address.builder()
                    .addressLine1("43 East Hague Extension")
                    .addressLine2("Quasoccaecat ut ear, 30055")
                    .country("Switzerland")
                    .build())
                .build(), 
            Consignor.builder()
                .name("EuroStore Services")
                .address(Address.builder()
                    .addressLine1("Rue de la Loi 200")
                    .addressLine2("1040 Brussels")
                    .country("Belgium")
                    .build())
                .build());
    }
}
