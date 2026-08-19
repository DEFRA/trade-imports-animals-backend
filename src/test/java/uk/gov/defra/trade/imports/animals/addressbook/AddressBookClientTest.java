package uk.gov.defra.trade.imports.animals.addressbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class AddressBookClientTest {

    private static final String BASE_URL = "http://address-book:8085";
    private static final String ORG_ID = "5900002";
    private static final String ADDRESS_ID = "665f1c2ab3e4d51a2c9d0e77";
    private static final String ADDRESS_URL =
        BASE_URL + "/organisation/" + ORG_ID + "/addresses/" + ADDRESS_ID;

    private static final String ADDRESS_JSON = """
        {
          "id": "665f1c2ab3e4d51a2c9d0e77",
          "name": "Astra Rosales",
          "addressLine1": "43 East Hague Extension",
          "addressLine2": null,
          "townOrCity": "Vernier",
          "county": "Soleure",
          "postcode": "30055",
          "countryCode": "CH",
          "phone": "+41 22 000 0000",
          "email": "astra@example.com",
          "organisationId": "5900002",
          "deleted": false,
          "createdAt": "2026-07-01T09:00:00Z",
          "modifiedAt": "2026-07-01T09:00:00Z"
        }
        """;

    private static final String ADDRESS_BY_ID_PATH =
        "/organisation/{orgId}/addresses/{addressId}";
    private static final AddressBookConfig ADDRESS_BOOK_CONFIG = new AddressBookConfig(
        BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(2), ADDRESS_BY_ID_PATH);

    private MockRestServiceServer server;
    private AddressBookClient addressBookClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        addressBookClient = new AddressBookClient(builder.build(), ADDRESS_BOOK_CONFIG);
    }

    @Nested
    class FindById {

        @Test
        void findById_shouldReturnTheRecord_andSendTheOrganisationInBothPathAndHeader() {
            // cv-010 — the address book requires the header to match the organisation in the path.
            server.expect(requestTo(ADDRESS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Trade-Imports-Organisation-Id", ORG_ID))
                .andRespond(withSuccess(ADDRESS_JSON, MediaType.APPLICATION_JSON));

            Optional<AddressBookRecord> result = addressBookClient.findById(ORG_ID, ADDRESS_ID);

            assertThat(result).contains(new AddressBookRecord(
                ADDRESS_ID, "Astra Rosales", "43 East Hague Extension", null, "Vernier",
                "Soleure", "30055", "CH", "+41 22 000 0000", "astra@example.com", false));
            server.verify();
        }

        @Test
        void findById_shouldReturnTheTombstone_whenTheAddressHasBeenSoftDeleted() {
            // A deletion answers 200 with deleted:true, not 404, so the caller can tell a deleted
            // address from one that never existed.
            server.expect(requestTo(ADDRESS_URL))
                .andRespond(withSuccess(ADDRESS_JSON.replace("\"deleted\": false", "\"deleted\": true"),
                    MediaType.APPLICATION_JSON));

            Optional<AddressBookRecord> result = addressBookClient.findById(ORG_ID, ADDRESS_ID);

            assertThat(result).isPresent();
            assertThat(result.get().deleted()).isTrue();
        }

        @Test
        void findById_shouldReturnEmpty_whenThereIsNoSuchAddress() {
            server.expect(requestTo(ADDRESS_URL)).andRespond(withResourceNotFound());

            assertThat(addressBookClient.findById(ORG_ID, ADDRESS_ID)).isEmpty();
        }

        @Test
        void findById_shouldPropagate_whenTheAddressBookIsUnavailable() {
            // An outage must not be indistinguishable from a deletion, so it is never swallowed
            // into an empty result.
            server.expect(requestTo(ADDRESS_URL)).andRespond(withServerError());

            assertThatThrownBy(() -> addressBookClient.findById(ORG_ID, ADDRESS_ID))
                .isInstanceOf(HttpServerErrorException.class);
        }

        @Test
        void findById_shouldTolerateUnknownFields_soTheAddressBookCanAddThem() {
            server.expect(requestTo(ADDRESS_URL))
                .andRespond(withSuccess(
                    ADDRESS_JSON.replaceFirst("\\{", "{\n  \"somethingNew\": \"value\","),
                    MediaType.APPLICATION_JSON));

            assertThat(addressBookClient.findById(ORG_ID, ADDRESS_ID)).isPresent();
        }
    }
}
