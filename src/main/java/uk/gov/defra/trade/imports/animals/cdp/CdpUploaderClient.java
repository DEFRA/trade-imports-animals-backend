package uk.gov.defra.trade.imports.animals.cdp;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.CdpUploaderInitiateRequest;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.CdpUploaderInitiateResponse;
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

  private static final int MAX_LOG_BODY_LENGTH = 500;

  private final RestClient cdpUploaderRestClient;

  /**
   * Initiates a new upload session with cdp-uploader.
   *
   * @param request the initiation parameters
   * @return the upload session details including the upload URL and upload ID
   * @throws ServiceUnavailableException if cdp-uploader returns a non-2xx response, or if the
   *     service is unreachable (connection refused, timeout, DNS failure, etc.). For transport-
   *     level failures the originating {@link RestClientException} is preserved as the cause.
   */
  public CdpUploaderInitiateResponse initiate(CdpUploaderInitiateRequest request) {
    log.debug("Initiating cdp-uploader session: s3Path={}", request.s3Path());
    try {
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
                String safeBody =
                    body.length() > MAX_LOG_BODY_LENGTH
                        ? body.substring(0, MAX_LOG_BODY_LENGTH) + "..."
                        : body;
                log.error(
                    "cdp-uploader returned non-2xx response: status={}, body={}",
                    statusCode,
                    safeBody);
                throw new ServiceUnavailableException(
                    "cdp-uploader returned an error response: HTTP " + statusCode);
              })
          .body(CdpUploaderInitiateResponse.class);
    } catch (ServiceUnavailableException e) {
      // Already wrapped by the onStatus handler — rethrow unchanged.
      throw e;
    } catch (RestClientException e) {
      // Transport-level failure (connection refused, timeout, DNS, etc.). Wrap so callers
      // see a single exception type for both upstream HTTP errors and unreachability.
      log.error("cdp-uploader request failed at transport level", e);
      throw new ServiceUnavailableException("cdp-uploader is unreachable: " + e.getMessage(), e);
    }
  }
}
