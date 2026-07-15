package uk.gov.defra.trade.imports.animals.operators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the operators service. Its <em>only</em> job is an existence check (c-017/c-018):
 * given an operator id it returns an {@link OperatorExistence} classification and discards every
 * field value in the response body. It deliberately exposes no operator-values type, so no future
 * increment can casually hydrate a notification from the check.
 *
 * <p>The caller's {@code Trade-Imports-Crn} is forwarded from request-scoped MDC (populated by the
 * request filter); the trace id is added transparently by the configured interceptor. An absent crn
 * is tolerated at this stage — the request simply carries no crn header.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OperatorsApiClient {

  private static final String CRN_HEADER = "Trade-Imports-Crn";
  private static final String MDC_CRN = "crn";
  private static final String STATUS_DELETED = "DELETED";

  private final RestClient operatorsRestClient;

  /**
   * Classifies the existence of one operator id.
   *
   * @param operatorId the operator id to check
   * @return {@link OperatorExistence#ACTIVE}/{@link OperatorExistence#DELETED} for a 200 (by the
   *     body's {@code status}), {@link OperatorExistence#NOT_FOUND} for a 404, or {@link
   *     OperatorExistence#UNAVAILABLE} for a transport failure/timeout or any other non-2xx — never
   *     conflating an outage with a deletion.
   */
  public OperatorExistence classify(String operatorId) {
    try {
      return operatorsRestClient
          .get()
          .uri("/operators/{operatorId}", operatorId)
          .headers(this::applyCrn)
          .exchange(
              (request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.value() == 404) {
                  return OperatorExistence.NOT_FOUND;
                }
                if (!status.is2xxSuccessful()) {
                  log.warn(
                      "operators existence check got unexpected status {} for operatorId={}",
                      status.value(),
                      operatorId);
                  return OperatorExistence.UNAVAILABLE;
                }
                OperatorStatusProbe probe = response.bodyTo(OperatorStatusProbe.class);
                return probe != null && STATUS_DELETED.equals(probe.status())
                    ? OperatorExistence.DELETED
                    : OperatorExistence.ACTIVE;
              });
    } catch (RestClientException e) {
      log.warn("operators existence check failed at transport level for operatorId={}", operatorId, e);
      return OperatorExistence.UNAVAILABLE;
    }
  }

  private void applyCrn(HttpHeaders headers) {
    String crn = MDC.get(MDC_CRN);
    if (crn != null && !crn.isBlank()) {
      headers.set(CRN_HEADER, crn);
    }
  }

  /**
   * Minimal probe of the operators response — only {@code status} is read; every other field is
   * discarded (c-017). Not an operator-values type.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OperatorStatusProbe(String status) {}
}
