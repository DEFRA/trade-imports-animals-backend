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
            operator("Astra Rosales", "43 East Hague Extension", "Quasoccaecat ut ear, 30055", "Switzerland"),
            operator("EuroStore Services", "Rue de la Loi 200", "1040 Brussels", "Belgium"));
    }

    public static List<Operator> destinations() {
        return List.of(
            operator("United Commerce", "446 Church Lane", "Manchester S1 2JE", "United Kingdom"),
            operator("Global Trading Co", "945 Main Street", "London LS1 5AB", "United Kingdom"));
    }

    public static List<Operator> consignments() {
        return List.of(
            operator("Animal and Plant Health Agency", "Woodham Lane", "New Haw, Addlestone", "United Kingdom"),
            operator("EuroStore Services", "8448 Gleason Creek", null, "France"));
    }

    public static List<Operator> placesOfOrigin() {
        return List.of(
            operator("Origin Farm", "1 Farm Lane", "County Clare", "Ireland"),
            operator("Nordic Livestock AS", "Fjordveien 12", "4010 Stavanger", "Norway"));
    }

    public static List<Operator> consignees() {
        return List.of(
            operator("British Livestock Ltd", "10 Market Street", "Leeds LS1 6HB", "United Kingdom"),
            operator("Northern Farms Co", "22 Barn Road", "York YO1 8AB", "United Kingdom"));
    }

    public static List<Operator> importers() {
        return List.of(
            operator("Import Co UK", "20 Trade Road", "London EC1A 1BB", "United Kingdom"),
            operator("GB Animal Imports", "5 Port Way", "Dover CT16 3AQ", "United Kingdom"));
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

    private static Operator operator(String name, String addressLine1, String addressLine2, String country) {
        Address.AddressBuilder address = Address.builder()
            .addressLine1(addressLine1)
            .country(country);
        if (addressLine2 != null) {
            address.addressLine2(addressLine2);
        }
        return Operator.builder()
            .name(name)
            .address(address.build())
            .build();
    }
}
