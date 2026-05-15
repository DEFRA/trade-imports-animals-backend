package uk.gov.defra.trade.imports.animals.cdp.uploader;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderInitiateRequest;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderInitiateResponse;
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
          .contentType(MediaType.APPLICATION_JSON)
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
    } catch (RestClientException e) {
      // Transport-level failure (connection refused, timeout, DNS, etc.) — distinct from
      // a non-2xx HTTP response, which the onStatus handler above turns into
      // ServiceUnavailableException directly. ServiceUnavailableException doesn't extend
      // RestClientException, so it propagates around this catch and isn't re-wrapped.
      log.error("cdp-uploader request failed at transport level", e);
      throw new ServiceUnavailableException("cdp-uploader is unreachable: " + e.getMessage(), e);
    }
  }

  /**
   * Proxies a multipart file to cdp-uploader's {@code /upload-and-scan/{uploadId}} endpoint.
   * Uses {@link MultipartFile#getResource()} so Spring's {@code ResourceHttpMessageConverter}
   * streams from the on-disk temp file rather than loading file bytes into heap.
   *
   * @param uploadId the upload session identifier
   * @param file     the multipart file to forward
   * @throws ServiceUnavailableException if cdp-uploader returns 4xx/5xx or is unreachable
   */
  public void uploadFile(String uploadId, MultipartFile file) {
    log.debug("Proxying file upload to cdp-uploader: uploadId={}", uploadId);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    HttpHeaders partHeaders = new HttpHeaders();
    String contentType = file.getContentType() != null
        ? file.getContentType() : "application/octet-stream";
    partHeaders.setContentType(MediaType.parseMediaType(contentType));
    partHeaders.setContentDisposition(
        ContentDisposition.formData()
            .name("file").filename(file.getOriginalFilename()).build());
    // MultipartFileResource overrides getFilename() and contentLength() correctly;
    // RestClient streams it via ResourceHttpMessageConverter — file bytes never sit in heap.
    body.add("file", new HttpEntity<>(file.getResource(), partHeaders));

    try {
      cdpUploaderRestClient
          .post()
          .uri("/upload-and-scan/{uploadId}", uploadId)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve()
          .onStatus(
              status -> status.is4xxClientError() || status.is5xxServerError(),
              (req, resp) -> {
                int statusCode = resp.getStatusCode().value();
                String responseBody;
                try (var is = resp.getBody()) {
                  responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                String safeBody = responseBody.length() > MAX_LOG_BODY_LENGTH
                    ? responseBody.substring(0, MAX_LOG_BODY_LENGTH) + "..."
                    : responseBody;
                log.error("cdp-uploader file upload returned non-2xx/3xx: status={}, body={}",
                    statusCode, safeBody);
                throw new ServiceUnavailableException(
                    "cdp-uploader file upload returned error: HTTP " + statusCode);
              })
          .toBodilessEntity();
      // cdp-uploader returns 302 on success — Java HttpClient's Redirect.NEVER default returns
      // it as-is; RestClient doesn't throw for 3xx; onStatus handler only catches 4xx/5xx.
    } catch (RestClientException e) {
      throw new ServiceUnavailableException("cdp-uploader file upload failed at transport level", e);
    }
  }
}
