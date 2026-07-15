package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NotificationModelSerialisationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void operator_shouldRoundTripExtendedFields_inCamelCase() throws Exception {
        Operator operator = Operator.builder()
            .operatorId("op-000123")
            .name("Astra Rosales")
            .telephone("+41 44 668 1800")
            .email("astra@example.ch")
            .address(Address.builder()
                .addressLine1("43 East Hague Extension")
                .addressLine2("Quasoccaecat")
                .addressLine3("Line three")
                .city("Zurich")
                .county("Zurich canton")
                .postcode("30055")
                .country("Switzerland")
                .build())
            .build();

        String json = objectMapper.writeValueAsString(operator);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("operatorId")).isTrue();
        assertThat(node.has("telephone")).isTrue();
        assertThat(node.has("email")).isTrue();
        assertThat(node.path("address").has("county")).isTrue();
        assertThat(node.path("address").has("postcode")).isTrue();

        Operator readBack = objectMapper.readValue(json, Operator.class);

        assertThat(readBack).isEqualTo(operator);
        assertThat(readBack.getOperatorId()).isEqualTo("op-000123");
        assertThat(readBack.getTelephone()).isEqualTo("+41 44 668 1800");
        assertThat(readBack.getEmail()).isEqualTo("astra@example.ch");
        assertThat(readBack.getAddress().getCounty()).isEqualTo("Zurich canton");
        assertThat(readBack.getAddress().getPostcode()).isEqualTo("30055");
    }

    @Test
    void transporter_shouldRoundTripExtendedFields_keepingApprovalNumberAndType() throws Exception {
        Transporter transporter = Transporter.builder()
            .operatorId("op-t-9001")
            .name("Garcia Livestock Transport SL")
            .telephone("+34 963 000 000")
            .email("ops@garcia-transport.es")
            .approvalNumber("ES-T2-45001294")
            .type("Commercial")
            .address(Address.builder()
                .addressLine1("46199 Brandy Dam")
                .county("Valencia")
                .postcode("46199")
                .country("Spain")
                .build())
            .build();

        String json = objectMapper.writeValueAsString(transporter);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("operatorId")).isTrue();
        assertThat(node.has("telephone")).isTrue();
        assertThat(node.has("email")).isTrue();
        assertThat(node.has("approvalNumber")).isTrue();
        assertThat(node.has("type")).isTrue();

        Transporter readBack = objectMapper.readValue(json, Transporter.class);

        assertThat(readBack).isEqualTo(transporter);
        assertThat(readBack.getOperatorId()).isEqualTo("op-t-9001");
        assertThat(readBack.getTelephone()).isEqualTo("+34 963 000 000");
        assertThat(readBack.getEmail()).isEqualTo("ops@garcia-transport.es");
        assertThat(readBack.getApprovalNumber()).isEqualTo("ES-T2-45001294");
        assertThat(readBack.getType()).isEqualTo("Commercial");
        assertThat(readBack.getAddress().getCounty()).isEqualTo("Valencia");
        assertThat(readBack.getAddress().getPostcode()).isEqualTo("46199");
    }

    @Test
    void legacyPayloadWithoutNewFields_shouldRoundTripUnchanged_andLeaveNewFieldsNull() throws Exception {
        String legacyOperatorJson = """
            {
              "name": "EuroStore Services",
              "address": {
                "addressLine1": "Rue de la Loi 200",
                "addressLine2": "1040 Brussels",
                "city": "Brussels",
                "country": "Belgium"
              }
            }
            """;

        Operator operator = objectMapper.readValue(legacyOperatorJson, Operator.class);

        assertThat(operator.getName()).isEqualTo("EuroStore Services");
        assertThat(operator.getAddress().getAddressLine1()).isEqualTo("Rue de la Loi 200");
        assertThat(operator.getAddress().getCity()).isEqualTo("Brussels");
        assertThat(operator.getAddress().getCountry()).isEqualTo("Belgium");
        assertThat(operator.getOperatorId()).isNull();
        assertThat(operator.getTelephone()).isNull();
        assertThat(operator.getEmail()).isNull();
        assertThat(operator.getAddress().getCounty()).isNull();
        assertThat(operator.getAddress().getPostcode()).isNull();

        Operator reRead = objectMapper.readValue(objectMapper.writeValueAsString(operator), Operator.class);
        assertThat(reRead).isEqualTo(operator);
    }
}
