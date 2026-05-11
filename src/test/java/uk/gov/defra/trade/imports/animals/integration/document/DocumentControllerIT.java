package uk.gov.defra.trade.imports.animals.integration.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.lifecycle.Startables;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentListResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentUploadResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;

/**
 * Integration tests for the accompanying document upload flow.
 *
 * <p>Drives the full upload→scan→callback round-trip against a real cdp-uploader Testcontainer:
 * the test posts a multipart file to cdp-uploader; cdp-uploader stores it in the LocalStack
 * quarantine bucket; an S3 → SQS notification triggers cdp-uploader's mock virus scanner; the
 * scanner publishes a result to {@code cdp-clamav-results}; cdp-uploader's listener picks it up,
 * promotes the file to the consumer bucket (CLEAN) or marks it rejected (INFECTED), and POSTs the
 * scan-result callback back to this backend.
 *
 * <p>Mock scanner behaviour: the scanner reads the upload's stored {@code encodedfilename} S3
 * metadata and tests it against {@code MOCK_VIRUS_REGEX} (default {@code .*virus.*}). So an
 * upload named {@code virus.pdf} comes back as INFECTED, anything else as CLEAN.
 *
 * <p>Defensive / edge-case scan callbacks (missing correlationId, mixed file results, etc.) live
 * in {@link DocumentScanCallbackIT} — they exercise our handler in shapes the real cdp-uploader
 * wouldn't produce, so the direct-POST fixture pattern is the right tool there.
 *
 * <p>Infrastructure:
 * <ul>
 *   <li>MongoDB — real Testcontainer from {@link IntegrationBase}
 *   <li>cdp-uploader — real {@code defradigital/cdp-uploader} Testcontainer
 *   <li>Redis — real Testcontainer (required by cdp-uploader)
 *   <li>LocalStack — S3 + SQS, with a quarantine→mock-clamav bucket notification wired up
 * </ul>
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class DocumentControllerIT extends IntegrationBase {

    private static final String NOTIFICATION_REF = "DRAFT.IMP.2025.IT001";
    private static final String AWS_REGION = "eu-west-2";

    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /**
     * Pre-allocated TCP port used by the embedded backend so that {@code cdp.backend.base-url} can
     * be configured before Spring starts. Without a known port up-front cdp-uploader has nowhere
     * to send the scan-result callback.
     */
    private static final int BACKEND_PORT = org.springframework.test.util.TestSocketUtils.findAvailableTcpPort();

    static final Network CONTAINER_NETWORK = Network.newNetwork();

    static final LocalStackContainer LOCAL_STACK_CONTAINER =
        CdpUploaderTestSupport.localStackContainer(CONTAINER_NETWORK, AWS_REGION);

    static final GenericContainer<?> REDIS_CONTAINER =
        CdpUploaderTestSupport.redisContainer(CONTAINER_NETWORK, "redis");

    static final GenericContainer<?> CDP_UPLOADER_CONTAINER =
        CdpUploaderTestSupport.withDevModeUploaderEnv(
            CdpUploaderTestSupport.cdpUploaderContainer(
                CONTAINER_NETWORK, "cdp-uploader", "redis",
                CdpUploaderTestSupport.DOCUMENTS_BUCKET + "," + CdpUploaderTestSupport.QUARANTINE_BUCKET),
            AWS_REGION);

    private static final S3Client localStackS3Client;
    private static final SqsClient localStackSqsClient;

    static {
        Startables.deepStart(LOCAL_STACK_CONTAINER, REDIS_CONTAINER).join();

        // Resolved at runtime — both env vars need LocalStack's container-internal endpoint.
        CDP_UPLOADER_CONTAINER.withEnv("SQS_ENDPOINT", "http://localstack:4566");
        CDP_UPLOADER_CONTAINER.withEnv(
            "SQS_SCAN_RESULTS_CALLBACK",
            "http://localstack:4566/000000000000/" + CdpUploaderTestSupport.SCAN_RESULTS_CALLBACK_QUEUE);

        localStackS3Client = CdpUploaderTestSupport.s3Client(LOCAL_STACK_CONTAINER, AWS_REGION);
        localStackSqsClient = CdpUploaderTestSupport.sqsClient(LOCAL_STACK_CONTAINER, AWS_REGION);

        localStackS3Client.createBucket(
            CreateBucketRequest.builder().bucket(CdpUploaderTestSupport.DOCUMENTS_BUCKET).build());
        localStackS3Client.createBucket(
            CreateBucketRequest.builder().bucket(CdpUploaderTestSupport.QUARANTINE_BUCKET).build());

        CdpUploaderTestSupport.wireScanQueuesAndBucketNotification(
            localStackSqsClient, localStackS3Client, AWS_REGION);

        LOCAL_STACK_CONTAINER.followOutput(
            new Slf4jLogConsumer(LoggerFactory.getLogger("localstack")));

        // Expose the backend's port BEFORE starting cdp-uploader so the port-forwarding ambassador
        // joins our custom Docker network — otherwise host.testcontainers.internal can't be DNS-
        // resolved from inside cdp-uploader and scan-result callbacks fail with ENOTFOUND.
        Testcontainers.exposeHostPorts(BACKEND_PORT);

        Startables.deepStart(CDP_UPLOADER_CONTAINER).join();

        log.info("LocalStack started; endpoint={}; buckets + queues + S3 notification ready",
            LOCAL_STACK_CONTAINER.getEndpoint());
        log.info("Backend will bind to port {} (host.testcontainers.internal:{})",
            BACKEND_PORT, BACKEND_PORT);
        log.info("cdp-uploader started on port {}", CDP_UPLOADER_CONTAINER.getMappedPort(CdpUploaderTestSupport.CDP_UPLOADER_PORT));
    }

    @DynamicPropertySource
    static void registerDocumentITProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> BACKEND_PORT);

        // From cdp-uploader's perspective, the backend lives on the host machine — the port
        // is tunnelled in via Testcontainers.exposeHostPorts above.
        registry.add("cdp.backend.base-url",
            () -> "http://host.testcontainers.internal:" + BACKEND_PORT);

        registry.add("cdp.uploader.base-url",
            () -> "http://" + CDP_UPLOADER_CONTAINER.getHost()
                + ":" + CDP_UPLOADER_CONTAINER.getMappedPort(CdpUploaderTestSupport.CDP_UPLOADER_PORT));

        registry.add("app.aws.endpoint-override",
            () -> LOCAL_STACK_CONTAINER.getEndpoint().toString());
        registry.add("aws.region", LOCAL_STACK_CONTAINER::getRegion);
        registry.add("app.aws.access-key-id", LOCAL_STACK_CONTAINER::getAccessKey);
        registry.add("app.aws.secret-access-key", LOCAL_STACK_CONTAINER::getSecretKey);

        registry.add("cdp.s3.documents-bucket", () -> CdpUploaderTestSupport.DOCUMENTS_BUCKET);

        registry.add("aws.sts.token.audience", () -> "test-audience");
        registry.add("aws.sts.token.expiration", () -> "3600");
    }

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @BeforeEach
    void setUpDocuments() {
        accompanyingDocumentRepository.deleteAll();
        assertThat(port).isEqualTo(BACKEND_PORT); // sanity: DEFINED_PORT honoured
    }

    // ---------------------------------------------------------------------------
    // Test: initiate upload — happy path
    // ---------------------------------------------------------------------------

    @Test
    void initiate_shouldReturn201AndPersistPendingDocument() {
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue(initiateBody())
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        DocumentUploadResponse responseBody = result.getResponseBody();
        assertThat(responseBody).isNotNull();
        String uploadId = responseBody.uploadId();
        assertThat(uploadId).isNotBlank();

        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
            .endsWith("/document-uploads/" + uploadId);

        assertThat(responseBody.uploadUrl()).contains("/upload-and-scan/" + uploadId);

        List<AccompanyingDocument> persisted =
            accompanyingDocumentRepository.findAllByNotificationReferenceNumber(NOTIFICATION_REF);
        assertThat(persisted).hasSize(1);
        AccompanyingDocument doc = persisted.get(0);
        assertThat(doc.getUploadId()).isEqualTo(uploadId);
        assertThat(doc.getNotificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(doc.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(doc.getDocumentType()).isEqualTo(DocumentType.ITAHC);
        assertThat(doc.getDocumentReference()).isEqualTo("UKGB2026001234");
        assertThat(doc.getFiles()).isEmpty();
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getCreated()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Test: real scan — clean file
    // ---------------------------------------------------------------------------

    /**
     * End-to-end CLEAN path: initiate, multipart-upload {@code clean.pdf} to cdp-uploader, await
     * the autonomous scan-result callback, and assert the persisted document landed at COMPLETE
     * with file metadata sourced from cdp-uploader's actual response (not a fixture).
     */
    @Test
    void scanResult_cleanFile_shouldEndUpAsCompleteViaRealScan() throws IOException {
        DocumentUploadResponse initiateResponse = initiateAndGetResponse();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");

        uploadFileViaCdpUploader(initiateResponse.uploadUrl(), "clean.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(initiateResponse.uploadId());
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.filename()).isEqualTo("clean.pdf");
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.fileStatus()).isEqualTo(FileStatus.COMPLETE);
        assertThat(file.contentLength()).isEqualTo(pdfBytes.length);
        assertThat(file.s3Key()).isNotBlank();
        assertThat(file.s3Bucket()).isEqualTo(CdpUploaderTestSupport.DOCUMENTS_BUCKET);
    }

    // ---------------------------------------------------------------------------
    // Test: real scan — virus file
    // ---------------------------------------------------------------------------

    /**
     * End-to-end INFECTED path: cdp-uploader's mock scanner flags any filename matching
     * {@code MOCK_VIRUS_REGEX} (default {@code .*virus.*}), so {@code virus.pdf} comes back
     * REJECTED with no s3Key.
     */
    @Test
    void scanResult_virusFile_shouldEndUpAsRejectedViaRealScan() throws IOException {
        DocumentUploadResponse initiateResponse = initiateAndGetResponse();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");

        uploadFileViaCdpUploader(initiateResponse.uploadUrl(), "virus.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(initiateResponse.uploadId());
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.filename()).isEqualTo("virus.pdf");
        assertThat(file.fileStatus()).isEqualTo(FileStatus.REJECTED);
        assertThat(file.s3Key()).isNull();
    }

    // ---------------------------------------------------------------------------
    // Test: list documents
    // ---------------------------------------------------------------------------

    @Test
    void listDocuments_shouldReturnDocumentsForNotification() {
        String uploadId = initiateAndGetUploadId();

        EntityExchangeResult<DocumentListResponse> result = webClient("NoAuth")
            .get()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .exchange()
            .expectStatus().isOk()
            .expectBody(DocumentListResponse.class)
            .returnResult();

        DocumentListResponse listResponse = result.getResponseBody();
        assertThat(listResponse).isNotNull();
        assertThat(listResponse.items()).hasSize(1);
        AccompanyingDocumentDto item = listResponse.items().get(0);
        assertThat(item.uploadId()).isEqualTo(uploadId);
        assertThat(item.notificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(item.scanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(item.id()).isNotNull();
        assertThat(item.documentType()).isEqualTo(DocumentType.ITAHC);
        assertThat(item.documentReference()).isEqualTo("UKGB2026001234");
        assertThat(item.dateOfIssue()).isEqualTo(java.time.Instant.parse("2026-01-15T00:00:00Z"));
    }

    // ---------------------------------------------------------------------------
    // Test: get by upload ID
    // ---------------------------------------------------------------------------

    @Test
    void getByUploadId_shouldReturn200WithCorrectDto() {
        String uploadId = initiateAndGetUploadId();

        EntityExchangeResult<AccompanyingDocumentDto> result = webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AccompanyingDocumentDto.class)
            .returnResult();

        AccompanyingDocumentDto dto = result.getResponseBody();
        assertThat(dto).isNotNull();
        assertThat(dto.uploadId()).isEqualTo(uploadId);
        assertThat(dto.notificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(dto.scanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(dto.id()).isNotNull();
        assertThat(dto.created()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Test: download streamed file from S3
    // ---------------------------------------------------------------------------

    @Test
    void downloadFile_shouldStreamFileFromS3WithCorrectHeaders() throws IOException {
        DocumentUploadResponse initiateResponse = initiateAndGetResponse();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");
        uploadFileViaCdpUploader(initiateResponse.uploadUrl(), "clean.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(initiateResponse.uploadId());
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);

        EntityExchangeResult<byte[]> result = webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + initiateResponse.uploadId() + "/file")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_PDF)
            .expectHeader().valueMatches(
                HttpHeaders.CONTENT_DISPOSITION,
                ".*filename\\*=UTF-8''clean\\.pdf.*")
            .expectBody(byte[].class)
            .returnResult();

        byte[] responseBytes = result.getResponseBody();
        assertThat(responseBytes).isNotNull().isEqualTo(pdfBytes);
    }

    @Test
    void getByUploadId_unknownId_shouldReturn404() {
        webClient("NoAuth")
            .get()
            .uri("/document-uploads/this-upload-id-does-not-exist")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value((String detail) ->
                assertThat(detail).contains("this-upload-id-does-not-exist"));
    }

    @Test
    void downloadFile_noFilesPresent_shouldReturn404() {
        String uploadId = initiateAndGetUploadId();

        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId + "/file")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value((String detail) ->
                assertThat(detail).contains(uploadId));
    }

    // ---------------------------------------------------------------------------
    // Test: dateOfIssue round-trip
    // ---------------------------------------------------------------------------

    @Test
    void initiate_withDateOfIssue_shouldPersistDateOfIssueAsInstant() {
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue(initiateBody())
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        String uploadId = result.getResponseBody().uploadId();

        AccompanyingDocument doc =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(doc.getDateOfIssue()).isNotNull();
        assertThat(doc.getDateOfIssue())
            .isEqualTo(java.time.Instant.parse("2026-01-15T00:00:00Z"));
    }

    // ---------------------------------------------------------------------------
    // Test: download rejected file (null s3Key) → 404
    // ---------------------------------------------------------------------------

    @Test
    void downloadFile_rejectedFile_shouldReturn404() throws IOException {
        DocumentUploadResponse initiateResponse = initiateAndGetResponse();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");
        uploadFileViaCdpUploader(initiateResponse.uploadUrl(), "virus.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(initiateResponse.uploadId());
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);

        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + initiateResponse.uploadId() + "/file")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404);
    }

    // ---------------------------------------------------------------------------
    // Test: S3 error during file download → 500
    // ---------------------------------------------------------------------------

    /**
     * Simulates "MongoDB says COMPLETE but S3 object has gone missing" by deleting the object
     * after the real scan completes. The endpoint must return 500 with a structured body rather
     * than hanging or leaking internals.
     */
    @Test
    void downloadFile_s3ObjectMissing_shouldReturn500() throws IOException {
        DocumentUploadResponse initiateResponse = initiateAndGetResponse();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");
        uploadFileViaCdpUploader(initiateResponse.uploadUrl(), "clean.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(initiateResponse.uploadId());
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        String s3Key = persisted.getFiles().get(0).s3Key();

        localStackS3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(CdpUploaderTestSupport.DOCUMENTS_BUCKET)
            .key(s3Key)
            .build());

        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + initiateResponse.uploadId() + "/file")
            .exchange()
            .expectStatus().is5xxServerError()
            .expectBody()
            .jsonPath("$.status").isEqualTo(500);
    }

    // ---------------------------------------------------------------------------
    // Test: delete
    // ---------------------------------------------------------------------------

    @Test
    void delete_shouldReturn204AndRemoveDocument() {
        String uploadId = initiateAndGetUploadId();

        webClient("NoAuth")
            .delete()
            .uri("/document-uploads/" + uploadId)
            .exchange()
            .expectStatus().isNoContent();

        assertThat(accompanyingDocumentRepository.findByUploadId(uploadId)).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String initiateBody() {
        return """
            {"documentType":"ITAHC","documentReference":"UKGB2026001234","dateOfIssue":"2026-01-15"}
            """;
    }

    private String initiateAndGetUploadId() {
        return initiateAndGetResponse().uploadId();
    }

    private DocumentUploadResponse initiateAndGetResponse() {
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue(initiateBody())
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        DocumentUploadResponse body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.uploadId()).isNotBlank();
        assertThat(body.uploadUrl()).isNotBlank();
        return body;
    }

    /**
     * POSTs a multipart {@code file} part to the upload URL the backend returned. Driving the
     * upload via {@code body.uploadUrl()} (rather than re-constructing the URL here) exercises
     * the backend's relative→absolute resolution end-to-end — a regression that left the URL
     * relative would fail this fetch with a URL-parse error rather than slipping through.
     */
    private void uploadFileViaCdpUploader(
        String uploadUrl, String filename, byte[] bytes, String contentType) {

        MultiValueMap<String, HttpEntity<?>> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        partHeaders.setContentDisposition(ContentDisposition.formData()
            .name("file").filename(filename).build());
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, partHeaders));

        WebClient.create()
            .post()
            .uri(uploadUrl)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(body))
            .retrieve()
            .toBodilessEntity()
            .block(Duration.ofSeconds(30));
    }

    /**
     * Polls MongoDB until cdp-uploader's autonomous scan-result callback has flipped the document
     * out of PENDING. Returns the persisted document so the caller can assert on the final state.
     */
    private AccompanyingDocument awaitScanCallback(String uploadId) {
        await()
            .atMost(SCAN_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .until(() -> {
                Optional<AccompanyingDocument> doc =
                    accompanyingDocumentRepository.findByUploadId(uploadId);
                return doc.isPresent() && doc.get().getScanStatus() != ScanStatus.PENDING;
            });
        return accompanyingDocumentRepository.findByUploadId(uploadId)
            .orElseThrow(() -> new AssertionError("Document vanished during scan: " + uploadId));
    }
}
