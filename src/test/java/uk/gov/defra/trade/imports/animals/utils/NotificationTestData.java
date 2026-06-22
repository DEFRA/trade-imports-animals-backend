package uk.gov.defra.trade.imports.animals.utils;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.Operator;
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

    public static List<Operator> consignors() {
        return List.of(
            Operator.builder()
                .name("Astra Rosales")
                .address(Address.builder()
                    .addressLine1("43 East Hague Extension")
                    .addressLine2("Quasoccaecat ut ear, 30055")
                    .country("Switzerland")
                    .build())
                .build(),
            Operator.builder()
                .name("EuroStore Services")
                .address(Address.builder()
                    .addressLine1("Rue de la Loi 200")
                    .addressLine2("1040 Brussels")
                    .country("Belgium")
                    .build())
                .build());
    }

    public static List<Operator> destinations() {
        return List.of(
            Operator.builder()
                .name("United Commerce")
                .address(Address.builder()
                    .addressLine1("446 Church Lane")
                    .addressLine2("Manchester S1 2JE")
                    .country("United Kingdom")
                    .build())
                .build(),
            Operator.builder()
                .name("Global Trading Co")
                .address(Address.builder()
                    .addressLine1("945 Main Street")
                    .addressLine2("London LS1 5AB")
                    .country("United Kingdom")
                    .build())
                .build());
    }

    public static List<Operator> consignments() {
        return List.of(
            Operator.builder()
                .name("Animal and Plant Health Agency")
                .address(Address.builder()
                    .addressLine1("Woodham Lane")
                    .addressLine2("New Haw")
                    .addressLine3("Addlestone")
                    .country("United Kingdom")
                    .build())
                .build(),
            Operator.builder()
                .name("EuroStore Services")
                .address(Address.builder()
                    .addressLine1("8448 Gleason Creek")
                    .country("France")
                    .build())
                .build());
    }

    public static List<Operator> placesOfOrigin() {
        return List.of(
            Operator.builder()
                .name("Origin Farm")
                .address(Address.builder()
                    .addressLine1("1 Farm Lane")
                    .addressLine2("County Clare")
                    .country("Ireland")
                    .build())
                .build(),
            Operator.builder()
                .name("Nordic Livestock AS")
                .address(Address.builder()
                    .addressLine1("Fjordveien 12")
                    .addressLine2("4010 Stavanger")
                    .country("Norway")
                    .build())
                .build());
    }

    public static List<Operator> consignees() {
        return List.of(
            Operator.builder()
                .name("British Livestock Ltd")
                .address(Address.builder()
                    .addressLine1("10 Market Street")
                    .addressLine2("Leeds LS1 6HB")
                    .country("United Kingdom")
                    .build())
                .build(),
            Operator.builder()
                .name("Northern Farms Co")
                .address(Address.builder()
                    .addressLine1("22 Barn Road")
                    .addressLine2("York YO1 8AB")
                    .country("United Kingdom")
                    .build())
                .build());
    }

    public static List<Operator> importers() {
        return List.of(
            Operator.builder()
                .name("Import Co UK")
                .address(Address.builder()
                    .addressLine1("20 Trade Road")
                    .addressLine2("London EC1A 1BB")
                    .country("United Kingdom")
                    .build())
                .build(),
            Operator.builder()
                .name("GB Animal Imports")
                .address(Address.builder()
                    .addressLine1("5 Port Way")
                    .addressLine2("Dover CT16 3AQ")
                    .country("United Kingdom")
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
