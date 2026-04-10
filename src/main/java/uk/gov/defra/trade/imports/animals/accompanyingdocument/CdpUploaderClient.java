package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
   */
  public CdpUploaderInitiateResponse initiate(CdpUploaderInitiateRequest request) {
    log.debug("Initiating cdp-uploader session");
    return cdpUploaderRestClient
        .post()
        .uri("/initiate")
        .contentType(APPLICATION_JSON)
        .body(request)
        .retrieve()
        .body(CdpUploaderInitiateResponse.class);
  }
}
