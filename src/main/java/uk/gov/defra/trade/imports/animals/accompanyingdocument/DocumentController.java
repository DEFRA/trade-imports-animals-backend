package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

/**
 * REST controller for accompanying document upload endpoints.
 *
 * <p>Exposes five endpoints covering the full upload lifecycle: initiation, listing,
 * retrieval, scan-result callback, and file download.
 */
@RestController
@Tag(name = "Document Uploads", description = "Operations for accompanying document upload sessions")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentService documentService;
  private final S3DocumentService s3DocumentService;
  private final CdpConfig cdpConfig;

  /**
   * Initiate a new document upload session for a notification.
   *
   * @param ref     the notification reference number
   * @param request the document metadata
   * @return 201 Created with the upload session details and a Location header
   */
  @PostMapping("/notifications/{ref}/document-uploads")
  @Operation(
      summary = "Initiate document upload",
      description = "Creates a new upload session via cdp-uploader and returns the upload URL")
  @ApiResponse(responseCode = "201", description = "Upload session created",
      content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class)))
  @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
  @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
  @Timed("document.initiate")
  public ResponseEntity<DocumentUploadResponse> initiate(
      @PathVariable String ref,
      @Valid @RequestBody DocumentUploadRequest request) {

    log.info("POST /notifications/{}/document-uploads", ref);

    DocumentUploadResponse response = documentService.initiate(ref, request);
    URI location = buildLocationUri(response.uploadId());

    return ResponseEntity.created(location).body(response);
  }

  /**
   * List all accompanying document upload sessions for a notification.
   *
   * @param ref the notification reference number
   * @return 200 OK with a list of accompanying document DTOs
   */
  @GetMapping("/notifications/{ref}/document-uploads")
  @Operation(
      summary = "List document uploads",
      description = "Returns all accompanying documents for a notification reference")
  @ApiResponse(responseCode = "200", description = "Document list returned",
      content = @Content(schema = @Schema(implementation = DocumentListResponse.class)))
  @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
  @Timed("document.list")
  public ResponseEntity<DocumentListResponse> list(@PathVariable String ref) {
    log.info("GET /notifications/{}/document-uploads", ref);
    List<AccompanyingDocumentDto> items = documentService.findByNotificationRef(ref).stream()
        .map(AccompanyingDocumentDto::from)
        .toList();
    return ResponseEntity.ok(new DocumentListResponse(items));
  }

  /**
   * Get a single accompanying document upload session by upload ID.
   *
   * @param uploadId the upload session identifier
   * @return 200 OK with the accompanying document DTO
   */
  @GetMapping("/document-uploads/{upload-id}")
  @Operation(
      summary = "Get document upload",
      description = "Returns an accompanying document by upload ID")
  @ApiResponse(responseCode = "200", description = "Document returned",
      content = @Content(schema = @Schema(implementation = AccompanyingDocumentDto.class)))
  @ApiResponse(responseCode = "404", description = "Upload not found", content = @Content)
  @Timed("document.get")
  public ResponseEntity<AccompanyingDocumentDto> get(
      @PathVariable("upload-id") String uploadId) {

    log.info("GET /document-uploads/{}", uploadId);
    AccompanyingDocument document = documentService.findByUploadId(uploadId);
    return ResponseEntity.ok(AccompanyingDocumentDto.from(document));
  }

  /**
   * Delete an accompanying document upload session.
   *
   * @param uploadId the upload session identifier
   * @return 204 No Content
   */
  @DeleteMapping("/document-uploads/{upload-id}")
  @Operation(
      summary = "Delete document upload",
      description = "Removes an accompanying document upload session from the notification")
  @ApiResponse(responseCode = "204", description = "Document deleted", content = @Content)
  @ApiResponse(responseCode = "404", description = "Upload not found", content = @Content)
  @Timed("document.delete")
  public ResponseEntity<Void> delete(@PathVariable("upload-id") String uploadId) {
    log.info("DELETE /document-uploads/{}", uploadId);
    documentService.deleteByUploadId(uploadId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Receive a scan-result callback from cdp-uploader.
   *
   * @param uploadId the upload session identifier
   * @param payload  the scan result payload
   * @return 204 No Content
   */
  // Security note (EUDPA-35): callback is currently unauthenticated; any caller able to reach
  // this endpoint with a known correlationId can post a scan result. Add a shared secret or
  // HMAC verification once cdp-uploader supports callback authentication.
  @PostMapping("/document-uploads/{upload-id}/scan-results")
  @Operation(
      summary = "Handle scan result",
      description = "Receives the cdp-uploader antivirus scan result callback")
  @ApiResponse(responseCode = "204", description = "Scan result processed", content = @Content)
  @Timed("document.scanResult")
  public ResponseEntity<Void> scanResult(
      @PathVariable("upload-id") String uploadId,
      @RequestBody CdpScanResultPayload payload) {

    // The path variable is always the literal "pending" set at initiate time; document identity
    // is resolved from metadata.correlationId in the payload, not from the URL.
    log.info("POST /document-uploads/{}/scan-results uploadStatus={}", uploadId,
        payload.uploadStatus());
    documentService.handleScanResult(uploadId, payload);
    return ResponseEntity.noContent().build();
  }

  /**
   * Download the file associated with a document upload session.
   *
   * <p>Each upload session contains exactly one file. The file is streamed directly from S3.
   *
   * @param uploadId the upload session identifier
   * @return 200 OK with the file content streamed from S3
   */
  @GetMapping("/document-uploads/{upload-id}/file")
  @Operation(
      summary = "Download uploaded file",
      description = "Streams the scanned file for an upload session from S3")
  @ApiResponse(responseCode = "200", description = "File streamed",
      content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
  @ApiResponse(responseCode = "404", description = "File not found", content = @Content)
  @Timed("document.downloadFile")
  public ResponseEntity<StreamingResponseBody> downloadFile(
      @PathVariable("upload-id") String uploadId) {

    log.info("GET /document-uploads/{}/file", uploadId);
    UploadedFile file = documentService.findFile(uploadId);

    StreamingResponseBody body = streamFromS3WithMdc(file);
    MediaType contentType = resolveContentType(uploadId, file.contentType());
    HttpHeaders headers = buildDownloadHeaders(contentType, file.filename());

    return ResponseEntity.ok()
        .headers(headers)
        .body(body);
  }

  private URI buildLocationUri(String uploadId) {
    String backendBaseUrl = cdpConfig.backend().baseUrl();
    if (backendBaseUrl.endsWith("/")) {
      backendBaseUrl = backendBaseUrl.substring(0, backendBaseUrl.length() - 1);
    }
    return URI.create(backendBaseUrl + "/document-uploads/" + uploadId);
  }

  /**
   * Builds the streaming response body. Captures the current MDC context and reapplies it on
   * the streaming thread so log lines emitted during S3 streaming carry the same trace IDs as
   * the original request.
   *
   * <p>Known limitation: if S3 throws mid-stream after response headers are committed, the
   * client receives a 200 with a truncated body and no error indication — inherent to
   * {@link StreamingResponseBody}, since headers are sent before any body bytes are written.
   * The pre-write failure case (e.g. NoSuchKey thrown before any bytes flow) still surfaces as
   * a 500 because no headers have been committed yet.
   */
  private StreamingResponseBody streamFromS3WithMdc(UploadedFile file) {
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    return outputStream -> {
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      try {
        s3DocumentService.streamToOutput(file.s3Key(), outputStream);
      } finally {
        MDC.clear();
      }
    };
  }

  private MediaType resolveContentType(String uploadId, String declared) {
    try {
      return MediaType.parseMediaType(declared);
    } catch (InvalidMediaTypeException _) {
      log.warn("GET /document-uploads/{}/file — invalid content-type '{}', falling back to application/octet-stream",
          uploadId, declared);
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  private static HttpHeaders buildDownloadHeaders(MediaType contentType, String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(contentType);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
    return headers;
  }
}
