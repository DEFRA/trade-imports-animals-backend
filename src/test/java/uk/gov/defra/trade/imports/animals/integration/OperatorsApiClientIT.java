package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.operators.OperatorExistence;
import uk.gov.defra.trade.imports.animals.operators.OperatorsApiClient;

/**
 * Integration test: the {@link OperatorsApiClient} resolves its base URL from {@code operators.url}
 * (fed by {@code TRADE_IMPORTS_OPERATORS_URL} in the running service, and by MockServer here via
 * {@link IntegrationBase#SERVICES_TO_MOCK}) and reaches the mocked operators service over a real
 * HTTP stack, forwarding the caller's {@code Trade-Imports-Crn} and classifying the response.
 */
class OperatorsApiClientIT extends IntegrationBase {

  @Autowired
  private OperatorsApiClient operatorsApiClient;

  @AfterEach
  void clearIdentity() {
    MDC.clear();
  }

  @Test
  void classifiesActiveOperator_forwardingCrn() {
    usingStub()
        .when(
            request()
                .withMethod("GET")
                .withPath(".*/OP-1")
                .withHeader("Trade-Imports-Crn", "GBCRN123"))
        .respond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"OP-1\",\"status\":\"ACTIVE\",\"name\":\"Acme\"}"));

    MDC.put("crn", "GBCRN123");

    assertThat(operatorsApiClient.classify("OP-1")).isEqualTo(OperatorExistence.ACTIVE);
  }

  @Test
  void classifiesUnknownOperatorAsNotFound() {
    usingStub()
        .when(request().withMethod("GET").withPath(".*/OP-404"))
        .respond(response().withStatusCode(404));

    MDC.put("crn", "GBCRN123");

    assertThat(operatorsApiClient.classify("OP-404")).isEqualTo(OperatorExistence.NOT_FOUND);
  }
}
