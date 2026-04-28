package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import uk.gov.defra.trade.imports.animals.exceptions.ServiceUnavailableException;

/**
 * HTTP client for the CDP uploader service.
 *
 * <p>Wraps the {@code cdpUploaderRestClient} bean configured in {@code RestClientConfig} with the
 * cdp-uploader base URL. Trace ID propagation is handled transparently by the configured
 * interceptor.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CdpUploaderClient {

  private final RestClient cdpUploaderRestClient;

  /**
   * Initiates a new upload session with cdp-uploader.
   *
   * @param request the initiation parameters
   * @return the upload session details including the upload URL and upload ID
   * @throws ServiceUnavailableException if cdp-uploader returns a non-2xx response
   */
  public CdpUploaderInitiateResponse initiate(CdpUploaderInitiateRequest request) {
    log.debug("Initiating cdp-uploader session");
    return cdpUploaderRestClient
        .post()
        .uri("/initiate")
        .contentType(APPLICATION_JSON)
        .body(request)
        .retrieve()
        .onStatus(
            status -> !status.is2xxSuccessful(),
            (req, resp) -> {
              int statusCode = resp.getStatusCode().value();
              String body;
              try (var is = resp.getBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
              }
              log.error(
                  "cdp-uploader returned non-2xx response: status={}, body={}", statusCode, body);
              throw new ServiceUnavailableException(
                  "cdp-uploader returned an error response: HTTP " + statusCode);
            })
        .body(CdpUploaderInitiateResponse.class);
  }
}
