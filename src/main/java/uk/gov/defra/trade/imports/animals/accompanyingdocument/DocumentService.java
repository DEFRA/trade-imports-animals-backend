package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderInitiateRequest;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderInitiateResponse;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpUploaderClient;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.ConflictException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.exceptions.ServiceUnavailableException;

/**
 * Business logic for accompanying document uploads.
 *
 * <p>Orchestrates calls to the cdp-uploader service and persists the resulting
 * {@link AccompanyingDocument} entities.
 */
@Service
@Slf4j
public class DocumentService {

  private final AccompanyingDocumentRepository accompanyingDocumentRepository;
  private final CdpUploaderClient cdpUploaderClient;
  private final String backendBaseUrl;
  private final CdpConfig cdpConfig;

  public DocumentService(
      AccompanyingDocumentRepository accompanyingDocumentRepository,
      CdpUploaderClient cdpUploaderClient,
      AppConfig appConfig,
      CdpConfig cdpConfig) {
    this.accompanyingDocumentRepository = accompanyingDocumentRepository;
    this.cdpUploaderClient = cdpUploaderClient;
    this.backendBaseUrl = appConfig.baseUrl();
    this.cdpConfig = cdpConfig;
  }

  /**
   * cdp-uploader's {@code /initiate} schema requires {@code xor('redirect', 'downloadUrls')}, so
   * we always pass a redirect path. Our flow is server-proxied (the frontend POSTs the file to
   * cdp-uploader on the user's behalf and ignores cdp-uploader's 302 response), so the value is
   * never user-visible — anything cdp-uploader accepts as a relative URI works. Hard-coded
   * because nothing in the caller's payload meaningfully affects it.
   */
  private static final String CDP_UPLOADER_REDIRECT_PATH = "/";

  /**
   * Initiates a new document upload session via cdp-uploader and persists a pending
   * {@link AccompanyingDocument}.
   *
   * <p>This method is <strong>not idempotent</strong>. cdp-uploader is always called to create a
   * new upload session. Multiple PENDING documents are deliberately permitted per notification —
   * the UX supports adding a second document while the first is still being scanned.
   *
   * <p>The {@code uploadId} returned by cdp-uploader is enforced as globally unique by a Mongo
   * unique index on the field. A duplicate would imply a cdp-uploader UUID collision; the
   * defensive catch below re-throws as {@link ConflictException} (HTTP 409). In that scenario the
   * cdp-uploader session is orphaned — no database record is saved for it.
   *
   * @param notificationRef the parent notification reference number
   * @param request         the document metadata supplied by the user
   * @return the upload session details (upload ID and upload URL)
   */
  public DocumentUploadResponse initiate(
      String notificationRef, DocumentUploadRequest request) {

    // Mint a backend-side correlationId before /initiate so it can ride in the metadata map
    // and come back to us on the scan callback. cdp-uploader assigns its own uploadId during
    // /initiate and locks the callback URL at that point; the callback payload doesn't echo
    // uploadId back, so correlationId is the only handle we control end-to-end.
    String correlationId = UUID.randomUUID().toString();

    CdpUploaderInitiateRequest initiateRequest = new CdpUploaderInitiateRequest(
        CDP_UPLOADER_REDIRECT_PATH,
        buildCallbackUrl(),
        cdpConfig.s3().documentsBucket(),
        notificationRef,
        cdpConfig.uploader().maxFileSize(),
        cdpConfig.uploader().mimeTypes(),
        buildMetadata(notificationRef, request, correlationId));

    log.info("Initiating cdp-uploader session for notification {}", notificationRef);
    CdpUploaderInitiateResponse response = cdpUploaderClient.initiate(initiateRequest);
    String uploadUrl = buildUploadUrl(response.uploadId());

    AccompanyingDocument document = buildPendingDocument(
        notificationRef, request, response, correlationId);
    saveOrThrowOnDuplicate(document, notificationRef);

    return new DocumentUploadResponse(response.uploadId(), uploadUrl);
  }

  /**
   * Constructs the file-proxy URL returned to the client in {@link DocumentUploadResponse}.
   * Points at this backend's own {@code POST /document-uploads/{uploadId}/file} endpoint so
   * the frontend (and the IT test assertions) use the backend as the upload target rather than
   * reaching cdp-uploader directly.
   *
   * <p>{@link UriComponentsBuilder#pathSegment} percent-encodes each segment individually and
   * appends to any existing path on {@code app.base-url}, preserving path prefixes.
   */
  private String buildUploadUrl(String uploadId) {
    return UriComponentsBuilder.fromUriString(backendBaseUrl)
        .pathSegment("document-uploads", uploadId, "file")
        .build()
        .toUriString();
  }

  /**
   * Proxies the uploaded file to cdp-uploader on behalf of the frontend.
   *
   * @param uploadId the upload session identifier
   * @param file     the multipart file received from the frontend
   * @throws NotFoundException           if no session exists for {@code uploadId}
   * @throws ServiceUnavailableException if cdp-uploader is unreachable or returns an error
   */
  public void proxyFileToUploader(String uploadId, MultipartFile file) {
    findByUploadId(uploadId);
    cdpUploaderClient.uploadFile(uploadId, file);
  }

  /**
   * Processes a cdp-uploader scan result callback, creating or updating the document record.
   * The document is resolved via {@code metadata.correlationId}. Under the direct-to-uploader
   * flow (EUDPA-106), the frontend calls cdp-uploader's {@code /initiate} itself and does not
   * pre-register the document with this service — the callback is where the record first appears.
   * Under the legacy backend-proxied flow, the record was created at initiate time; the callback
   * updates it.
   *
   * <p>On the create branch, {@code documentType}, {@code documentReference}, {@code dateOfIssue}
   * and {@code notificationReferenceNumber} are taken from the callback body: notification from
   * {@code metadata}, the three text fields from {@code payload.form()} (the multipart form
   * values the browser submitted alongside the file — cdp-uploader's README documents that text
   * form fields are preserved as-is in the callback).
   *
   * @param uploadId the upload session identifier from the callback path; informational only
   *                 (kept for log breadcrumbs — resolution is via {@code metadata.correlationId})
   * @param payload  the callback payload from cdp-uploader
   * @throws BadRequestException if the payload metadata does not include a correlationId
   */
  public void handleScanResult(String uploadId, CdpScanResultPayload payload) {
    String correlationId = extractCorrelationId(payload);
    AccompanyingDocument document = accompanyingDocumentRepository
        .findByCorrelationId(correlationId)
        .orElseGet(() -> buildDocumentFromScanResult(correlationId, payload));

    document.setFiles(mapUploadedFiles(payload));
    document.setScanStatus(resolveScanStatus(payload));

    accompanyingDocumentRepository.save(document);
    log.info("Persisted uploadId {} scanStatus {}", document.getUploadId(), document.getScanStatus());
  }

  /**
   * Constructs a new {@link AccompanyingDocument} from a cdp-uploader scan-result payload — used
   * when no pre-registered document exists (EUDPA-106 direct-to-uploader flow, where the frontend
   * calls cdp-uploader's {@code /initiate} itself). Reads {@code notificationReferenceNumber} and
   * {@code uploadId} from {@code metadata} and the three text fields ({@code documentType},
   * {@code documentReference}, {@code dateOfIssue} — the GDS day/month/year triple) from
   * {@code payload.form().getTextFields()}.
   *
   * <p>Every field is required. Any missing, blank, or unparseable value triggers
   * {@link BadRequestException} — a partially-built document would be undefined behaviour at the
   * display layer and would leak into audit records as a silent data-quality issue.
   */
  private AccompanyingDocument buildDocumentFromScanResult(
      String correlationId, CdpScanResultPayload payload) {
    Map<String, String> metadata = payload.metadata();
    Map<String, String> formText = payload.form() != null
        ? payload.form().getTextFields()
        : Map.of();

    String notificationRef = requireCallbackField(metadata, "metadata", "notificationReferenceNumber");
    DocumentType documentType = DocumentType.parse(formText.get("documentType"))
        .orElseThrow(() -> new BadRequestException(
            "Scan callback form.documentType is missing or not a known DocumentType value"));
    String documentReference = requireCallbackField(formText, "form", "documentReference");
    java.time.Instant dateOfIssue = parseIssueDate(formText);

    return AccompanyingDocument.builder()
        .notificationReferenceNumber(notificationRef)
        .uploadId(metadata.getOrDefault("uploadId", correlationId))
        .correlationId(correlationId)
        .documentType(documentType)
        .documentReference(documentReference)
        .dateOfIssue(dateOfIssue)
        .files(new ArrayList<>())
        .build();
  }

  /**
   * Reads a required string field from a scan-callback map (either {@code metadata} or the
   * {@code form.textFields} map), throwing {@link BadRequestException} if the value is
   * {@code null}, blank, or whitespace-only. The {@code section} argument is used only to
   * qualify the error message so a 400 log-line points at the callback body location without
   * ambiguity — e.g. {@code "metadata.notificationReferenceNumber"} vs
   * {@code "form.notificationReferenceNumber"}.
   *
   * <p>Package-private for direct unit-testing.
   */
  static String requireCallbackField(Map<String, String> source, String section, String key) {
    String value = source.get(key);
    if (value == null || value.isBlank()) {
      throw new BadRequestException(
          "Scan callback " + section + "." + key + " is missing or blank");
    }
    return value;
  }

  /**
   * Parses the GDS date-input triple ({@code issueDate-day/month/year}) from the callback's form
   * fields into a UTC {@link java.time.Instant} at midnight. Throws {@link BadRequestException} if
   * any of the three fields is missing or if the combined values do not form a valid calendar
   * date — mirrors the required-field policy applied to the other callback-form fields.
   *
   * <p>Package-private for direct unit-testing.
   */
  static java.time.Instant parseIssueDate(Map<String, String> formText) {
    String day = requireCallbackField(formText, "form", "issueDate-day");
    String month = requireCallbackField(formText, "form", "issueDate-month");
    String year = requireCallbackField(formText, "form", "issueDate-year");
    try {
      return java.time.LocalDate.of(
          Integer.parseInt(year),
          Integer.parseInt(month),
          Integer.parseInt(day))
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant();
    } catch (NumberFormatException | java.time.DateTimeException e) {
      throw new BadRequestException(
          "Scan callback form.issueDate is not a valid date: "
              + day + "/" + month + "/" + year);
    }
  }

  /**
   * Deletes the accompanying document with the given upload ID.
   *
   * @param uploadId the upload session identifier
   * @throws NotFoundException if not found
   */
  public void deleteByUploadId(String uploadId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    accompanyingDocumentRepository.delete(document);
    log.info("Deleted AccompanyingDocument with uploadId {}", uploadId);
  }

  /**
   * Returns all accompanying documents for the given notification reference.
   *
   * @param ref the notification reference number
   * @return list of documents, possibly empty
   */
  public List<AccompanyingDocument> findByNotificationRef(String ref) {
    return accompanyingDocumentRepository.findAllByNotificationReferenceNumber(ref);
  }

  /**
   * Deletes all accompanying documents belonging to the given notification reference numbers.
   * Called as part of cascade deletion when notifications are removed.
   *
   * @param referenceNumbers the parent notification reference numbers whose documents should be deleted
   */
  public void deleteForNotificationRefs(List<String> referenceNumbers) {
    Objects.requireNonNull(referenceNumbers, "referenceNumbers must not be null");
    log.info("Cascade deleting accompanying documents for {} notification(s)", referenceNumbers.size());
    accompanyingDocumentRepository.deleteAllByNotificationReferenceNumberIn(referenceNumbers);
  }

  /**
   * Returns the accompanying document with the given upload ID.
   *
   * @param uploadId the upload session identifier
   * @return the document
   * @throws NotFoundException if not found
   */
  public AccompanyingDocument findByUploadId(String uploadId) {
    return accompanyingDocumentRepository.findByUploadId(uploadId)
        .orElseThrow(
            () -> new NotFoundException("No accompanying document found with uploadId: " + uploadId));
  }

  /**
   * Returns the uploaded file for the given upload session.
   *
   * <p>The upload workflow enforces a one-file-per-session invariant, so {@code findFirst()} is
   * safe here. This method does not enforce that invariant structurally — it relies on the workflow
   * contract. Throws {@link NotFoundException} if the session has no file or if the file was
   * rejected (s3Key is null).
   *
   * @param uploadId the upload session identifier
   * @return the uploaded file record
   * @throws NotFoundException if the document has no file or the file was rejected
   */
  public UploadedFile findFile(String uploadId) {
    AccompanyingDocument document = findByUploadId(uploadId);
    UploadedFile file = document.getFiles().stream()
        .findFirst()
        .orElseThrow(
            () -> new NotFoundException(
                "No file found for uploadId: " + uploadId));
    if (file.s3Key() == null) {
      throw new NotFoundException(
          "File for uploadId: " + uploadId + " was rejected and is not available for download");
    }
    return file;
  }

  /**
   * Callback URL is fixed at initiate time — cdp-uploader validates it as a URI and posts to it
   * verbatim, with no template substitution. The path's "pending" segment is a static marker;
   * document identity is carried via {@code metadata.correlationId}, not the URL.
   */
  private String buildCallbackUrl() {
    return backendBaseUrl + "/document-uploads/pending/scan-results";
  }

  /**
   * Builds the metadata map for a cdp-uploader initiate request. {@code documentType} and
   * {@code documentReference} are non-null per {@link DocumentUploadRequest}'s {@code @NotNull} /
   * {@code @NotBlank} constraints (validated at the controller boundary), so no null-fallback is
   * needed here.
   */
  private static Map<String, String> buildMetadata(
      String notificationRef, DocumentUploadRequest request, String correlationId) {
    return Map.of(
        "notificationReferenceNumber", notificationRef,
        "documentType", request.documentType().name(),
        "documentReference", request.documentReference(),
        "correlationId", correlationId);
  }

  private static AccompanyingDocument buildPendingDocument(
      String notificationRef,
      DocumentUploadRequest request,
      CdpUploaderInitiateResponse response,
      String correlationId) {
    return AccompanyingDocument.builder()
        .notificationReferenceNumber(notificationRef)
        .uploadId(response.uploadId())
        .correlationId(correlationId)
        .documentType(request.documentType())
        .documentReference(request.documentReference())
        .dateOfIssue(request.dateOfIssue().atStartOfDay(ZoneOffset.UTC).toInstant())
        .scanStatus(ScanStatus.PENDING)
        .files(new ArrayList<>())
        .build();
  }

  private void saveOrThrowOnDuplicate(AccompanyingDocument document, String notificationRef) {
    try {
      accompanyingDocumentRepository.save(document);
      log.info(
          "Saved AccompanyingDocument with uploadId {} for notification {}",
          document.getUploadId(),
          notificationRef);
    } catch (DuplicateKeyException _) {
      log.warn(
          "Duplicate uploadId {} from cdp-uploader for notification {} — UUID collision",
          document.getUploadId(),
          notificationRef);
      throw new ConflictException(
          "Upload session with id " + document.getUploadId() + " already exists");
    }
  }

  private static String extractCorrelationId(CdpScanResultPayload payload) {
    String correlationId = payload.metadata().get("correlationId");
    if (correlationId == null || correlationId.isBlank()) {
      throw new BadRequestException(
          "Scan callback missing required correlationId in metadata");
    }
    return correlationId;
  }

  private static List<UploadedFile> mapUploadedFiles(CdpScanResultPayload payload) {
    List<UploadedFile> files = new ArrayList<>();
    if (payload.form() != null) {
      for (CdpScanResultFile entry : payload.form().getFiles().values()) {
        files.add(UploadedFile.from(entry));
      }
    }
    return files;
  }

  /**
   * Fail-closed: treat a missing or non-zero rejected count as REJECTED to avoid silently
   * accepting a file when cdp-uploader omits the field.
   */
  private static ScanStatus resolveScanStatus(CdpScanResultPayload payload) {
    Integer rejectedCount = payload.numberOfRejectedFiles();
    return rejectedCount != null && rejectedCount == 0
        ? ScanStatus.COMPLETE
        : ScanStatus.REJECTED;
  }
}
