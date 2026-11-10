package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.validation.annotation.Validated;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;

/**
 * REST controller for accompanying document upload endpoints.
 */
@RestController
@Validated
@Tag(name = "Document Uploads", description = "Operations for accompanying document upload sessions")
@Slf4j
public class DocumentController {

  private static final String UPLOAD_SESSION_IDENTIFIER_PATTERN = "^[A-Za-z0-9._-]{1,100}$";

  private final DocumentService documentService;
  private final S3DocumentService s3DocumentService;
  private final String backendBaseUrl;

  public DocumentController(
      DocumentService documentService,
      S3DocumentService s3DocumentService,
      AppConfig appConfig) {
    this.documentService = documentService;
    this.s3DocumentService = s3DocumentService;
    this.backendBaseUrl = appConfig.baseUrl();
  }

  /**
   * Register a cdp-uploader upload session for a notification.
   *
   * @param referenceNumber the notification reference number
   * @param request the upload session's identifiers
   * @return 201 Created with the recorded session and a Location header
   */
  @PostMapping("/notifications/{referenceNumber}/document-uploads")
  @Operation(
      summary = "Register document upload",
      description = "Records an upload session so what arrives through it can be matched")
  @ApiResponse(responseCode = "201", description = "Upload session registered",
      content = @Content(schema = @Schema(implementation = UploadSessionDto.class)))
  @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
  @ApiResponse(responseCode = "409", description = "Upload session already registered",
      content = @Content)
  @Timed("document.register")
  public ResponseEntity<UploadSessionDto> register(
      @PathVariable @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
      String referenceNumber,
      @Valid @RequestBody RegisterUploadRequest request) {

    log.info("POST /notifications/{}/document-uploads uploadId={}", referenceNumber,
        request.uploadId());

    UploadSession session = documentService.registerUpload(referenceNumber, request);
    URI location = buildLocationUri(session.getUploadId());

    return ResponseEntity.created(location).body(UploadSessionDto.from(session));
  }

  /**
   * Look up the upload session a notification opened under a correlationId.
   *
   * @param referenceNumber the notification reference number
   * @param correlationId the correlationId the session was opened under
   * @return 200 OK with the session
   */
  @GetMapping("/notifications/{referenceNumber}/document-upload-sessions/{correlationId}")
  @Operation(
      summary = "Get document upload session",
      description = "Returns the upload session a notification opened under a correlationId")
  @ApiResponse(responseCode = "200", description = "Session returned",
      content = @Content(schema = @Schema(implementation = UploadSessionDto.class)))
  @ApiResponse(responseCode = "404", description = "Session not found", content = @Content)
  @Timed("document.session")
  public ResponseEntity<UploadSessionDto> session(
      @PathVariable @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
      String referenceNumber,
      @PathVariable
      @Pattern(regexp = UPLOAD_SESSION_IDENTIFIER_PATTERN) String correlationId) {

    log.info("GET /notifications/{}/document-upload-sessions/{}", referenceNumber,
        correlationId);

    return ResponseEntity.ok(UploadSessionDto.from(
        documentService.findSession(referenceNumber, correlationId)));
  }

  /**
   * Claim a registered upload into its notification, recording the submitted document details.
   *
   * @param uploadId the upload session identifier
   * @param request  the document details
   * @return 200 OK with the claimed document
   */
  @PostMapping("/document-uploads/{uploadId}/claim")
  @Operation(
      summary = "Claim document upload",
      description = "Attaches the user's document details to a registered upload and claims it")
  @ApiResponse(responseCode = "200", description = "Document claimed",
      content = @Content(schema = @Schema(implementation = AccompanyingDocumentDto.class)))
  @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
  @ApiResponse(responseCode = "404", description = "Upload not found", content = @Content)
  @Timed("document.claim")
  public ResponseEntity<AccompanyingDocumentDto> claim(
      @PathVariable
      @Pattern(regexp = UPLOAD_SESSION_IDENTIFIER_PATTERN) String uploadId,
      @Valid @RequestBody ClaimUploadRequest request) {

    log.info("POST /document-uploads/{}/claim", uploadId);

    AccompanyingDocument document = documentService.claimUpload(uploadId, request);

    return ResponseEntity.ok(AccompanyingDocumentDto.from(document));
  }

  /**
   * List all accompanying document upload sessions for a notification.
   *
   * @param referenceNumber the notification reference number
   * @return 200 OK with a list of accompanying document DTOs
   */
  @GetMapping("/notifications/{referenceNumber}/document-uploads")
  @Operation(
      summary = "List document uploads",
      description = "Returns all accompanying documents for a notification reference number")
  @ApiResponse(responseCode = "200", description = "Document list returned",
      content = @Content(schema = @Schema(implementation = DocumentListResponse.class)))
  @ApiResponse(responseCode = "404", description = "Notification not found", content = @Content)
  @Timed("document.list")
  public ResponseEntity<DocumentListResponse> list(
      @PathVariable String referenceNumber) {
    log.info("GET /notifications/{}/document-uploads", referenceNumber);
    List<AccompanyingDocumentDto> items = documentService
        .findByNotificationReferenceNumber(referenceNumber).stream()
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
  @GetMapping("/document-uploads/{uploadId}")
  @Operation(
      summary = "Get document upload",
      description = "Returns an accompanying document by upload ID")
  @ApiResponse(responseCode = "200", description = "Document returned",
      content = @Content(schema = @Schema(implementation = AccompanyingDocumentDto.class)))
  @ApiResponse(responseCode = "404", description = "Upload not found", content = @Content)
  @Timed("document.get")
  public ResponseEntity<AccompanyingDocumentDto> get(
      @PathVariable String uploadId) {

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
  @DeleteMapping("/document-uploads/{uploadId}")
  @Operation(
      summary = "Delete document upload",
      description = "Removes an accompanying document upload session from the notification")
  @ApiResponse(responseCode = "204", description = "Document deleted", content = @Content)
  @ApiResponse(responseCode = "404", description = "Upload not found", content = @Content)
  @Timed("document.delete")
  public ResponseEntity<Void> delete(@PathVariable String uploadId) {
    log.info("DELETE /document-uploads/{}", uploadId);
    documentService.deleteByUploadId(uploadId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Receive a scan-result callback from cdp-uploader.
   *
   * @param pathSegment the fixed segment the session's callback URL was opened with
   * @param payload     the scan result payload
   * @return 204 No Content
   */
  @PostMapping("/document-uploads/{pathSegment}/scan-results")
  @Operation(
      summary = "Handle scan result",
      description = "Receives the cdp-uploader antivirus scan result callback")
  @ApiResponse(responseCode = "204", description = "Scan result processed", content = @Content)
  @Timed("document.scanResult")
  public ResponseEntity<Void> scanResult(
      @PathVariable String pathSegment,
      @RequestBody CdpScanResultPayload payload) {

    log.info("POST /document-uploads/{}/scan-results correlationId={} uploadStatus={}", pathSegment,
        payload.correlationId(), payload.uploadStatus());
    documentService.handleScanResult(payload);
    return ResponseEntity.noContent().build();
  }

  /**
   * Download the first uploaded file of a document upload session.
   *
   * @param uploadId the upload session identifier
   * @return 200 OK with the file content streamed from S3
   */
  @GetMapping("/document-uploads/{uploadId}/file")
  @Operation(
      summary = "Download uploaded file",
      description = "Streams the scanned file for an upload session from S3")
  @ApiResponse(responseCode = "200", description = "File streamed",
      content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
  @ApiResponse(responseCode = "404", description = "File not found", content = @Content)
  @Timed("document.downloadFile")
  public ResponseEntity<StreamingResponseBody> downloadFile(
      @PathVariable String uploadId) {

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
    String baseUrl = backendBaseUrl;
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    return URI.create(baseUrl + "/document-uploads/" + uploadId);
  }

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
