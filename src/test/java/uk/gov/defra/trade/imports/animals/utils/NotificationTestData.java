package uk.gov.defra.trade.imports.animals.utils;

import java.util.List;
import uk.gov.defra.trade.imports.animals.notification.Address;
import uk.gov.defra.trade.imports.animals.notification.ConsignmentParty;
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

    public static List<ConsignmentParty> consignors() {
        return List.of(
            party("Astra Rosales", "43 East Hague Extension", "Vernier", "30055", "CH"),
            party("EuroStore Services", "Rue de la Loi 200", "Brussels", "1040", "BE"));
    }

    public static List<ConsignmentParty> destinations() {
        return List.of(
            party("United Commerce", "446 Church Lane", "Manchester", "S1 2JE", "GB"),
            party("Global Trading Co", "945 Main Street", "London", "LS1 5AB", "GB"));
    }

    public static List<ConsignmentParty> consignments() {
        return List.of(
            party("Animal and Plant Health Agency", "Woodham Lane", "Addlestone", "KT15 3NB", "GB"),
            party("EuroStore Services", "8448 Gleason Creek", "Calais", "62100", "FR"));
    }

    public static List<ConsignmentParty> placesOfOrigin() {
        return List.of(
            party("Origin Farm", "1 Farm Lane", "Ennis", "V95 X7P2", "IE"),
            party("Nordic Livestock AS", "Fjordveien 12", "Stavanger", "4010", "NO"));
    }

    public static List<ConsignmentParty> consignees() {
        return List.of(
            party("British Livestock Ltd", "10 Market Street", "Leeds", "LS1 6HB", "GB"),
            party("Northern Farms Co", "22 Barn Road", "York", "YO1 8AB", "GB"));
    }

    public static List<ConsignmentParty> importers() {
        return List.of(
            party("Import Co UK", "20 Trade Road", "London", "EC1A 1BB", "GB"),
            party("GB Animal Imports", "5 Port Way", "Dover", "CT16 3AQ", "GB"));
    }

    /** A party held as an address-book reference — no details until it is resolved on read. */
    public static ConsignmentParty reference(String addressId) {
        return ConsignmentParty.builder().addressId(addressId).build();
    }

    public static List<Transporter> transporters() {
        return List.of(
            Transporter.builder()
                .name("García Livestock Transport SL")
                .address(address("46199 Brandy Dam, Suite 368", "Vernier", "2051", "CH"))
                .approvalNumber("ES-T2-45001294")
                .type("Commercial")
                .build(),
            Transporter.builder()
                .name("J & G Campbell LTD")
                .address(address("Noahplein 627b, 3e verdieping", "Lauwe", "1836", "BE"))
                .approvalNumber("DE/BURY/T2/00104115")
                .type("Private")
                .build(),
            Transporter.builder()
                .name("John Gosden LTD")
                .address(address("67 Old Saffron Lane", "Bury", "LE2 7FT", "GB"))
                .approvalNumber("UK/BURY/T2/00104115")
                .type("Private")
                .build()
        );
    }

    private static ConsignmentParty party(
        String name, String addressLine1, String townOrCity, String postcode, String countryCode) {
        return ConsignmentParty.builder()
            .name(name)
            .email("contact@example.com")
            .phone("+44 1234 567890")
            .address(address(addressLine1, townOrCity, postcode, countryCode))
            .build();
    }

    private static Address address(
        String addressLine1, String townOrCity, String postcode, String countryCode) {
        return Address.builder()
            .addressLine1(addressLine1)
            .townOrCity(townOrCity)
            .postcode(postcode)
            .countryCode(countryCode)
            .build();
    }
}
