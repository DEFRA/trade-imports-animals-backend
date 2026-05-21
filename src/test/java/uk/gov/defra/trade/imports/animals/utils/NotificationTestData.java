package uk.gov.defra.trade.imports.animals.utils;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.Consignment;
import uk.gov.defra.trade.imports.animals.notification.Consignor;
import uk.gov.defra.trade.imports.animals.notification.ContactAddress;
import uk.gov.defra.trade.imports.animals.notification.Destination;
import uk.gov.defra.trade.imports.animals.notification.Species;
import uk.gov.defra.trade.imports.animals.notification.Transporter;

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
    
    public static List<Destination> destinations() {
        return List.of(
            Destination.builder()
                .name("United Commerce")
                .address(Address.builder()
                    .addressLine1("446 Church Lane")
                    .addressLine2("Manchester S1 2JE")
                    .country("United Kingdom")
                    .build())
                .build(),
            Destination.builder()
                .name("Global Trading Co")
                .address(Address.builder()
                    .addressLine1("945 Main Street")
                    .addressLine2("London LS1 5AB")
                    .country("United Kingdom")
                    .build())
                .build()
        );
    }
    
    public static List<Consignment> consignments() {
        return List.of(
            Consignment.builder()
                .contact(ContactAddress.builder()
                    .name("Animal and Plant Health Agency")
                    .address(Address.builder()
                        .addressLine1("Woodham Lane")
                        .addressLine2("New Haw")
                        .addressLine3("Addlestone")
                        .country("United Kingdom")
                        .build())
                    .build())
                .build(),
            Consignment.builder()
                .contact(ContactAddress.builder()
                    .name("EuroStore Services")
                    .address(Address.builder()
                        .addressLine1("8448 Gleason Creek")
                        .country("France")
                        .build())
                    .build())
                .build());
    }

    public static List<Transporter> transporters() {
        return List.of(
            Transporter.builder()
                .name("García Livestock Transport SL")
                .address(Address.builder()
                    .addressLine1("46199 Brandy Dam, Suite 368, 2051")
                    .addressLine2("5298, Vernier, Soleure")
                    .country("Switzerland")
                    .build())
                .approvalNumber("ES-T2-45001294")
                .type("Commercial")
                .build(),
            Transporter.builder()
                .name("J & G Campbell LTD")
                .address(Address.builder()
                    .addressLine1("Noahplein 627b, 3e verdieping")
                    .addressLine2("1836, Lauwe, Vlaams-Brabant")
                    .country("Belgium")
                    .build())
                .approvalNumber("DE/BURY/T2/00104115")
                .type("Private")
                .build(),
            Transporter.builder()
                .name("John Gosden LTD")
                .address(Address.builder()
                    .addressLine1("67 Old Saffron Lane")
                    .addressLine2("BURY, LE2 7FT")
                    .country("UK")
                    .build())
                .approvalNumber("UK/BURY/T2/00104115")
                .type("Private")
                .build()
        );
    }
}
