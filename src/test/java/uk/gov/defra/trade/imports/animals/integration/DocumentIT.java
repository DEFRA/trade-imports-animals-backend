package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentListResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentUploadResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;

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
class DocumentIT extends IntegrationBase {

    private static final String DOCUMENTS_BUCKET = "trade-imports-animals-documents";
    private static final String QUARANTINE_BUCKET = "cdp-uploader-quarantine";
    private static final String NOTIFICATION_REF = "DRAFT.IMP.2025.IT001";

    private static final String MOCK_CLAMAV_QUEUE = "mock-clamav";
    private static final String SCAN_RESULTS_QUEUE = "cdp-clamav-results";
    private static final String SCAN_RESULTS_CALLBACK_QUEUE = "cdp-uploader-scan-results-callback.fifo";
    private static final String DOWNLOAD_REQUESTS_QUEUE = "cdp-uploader-download-requests";

    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /**
     * Pre-allocated TCP port used by the embedded backend so that {@code cdp.backend.base-url} can
     * be configured before Spring starts. Without a known port up-front cdp-uploader has nowhere
     * to send the scan-result callback.
     */
    private static final int BACKEND_PORT = org.springframework.test.util.TestSocketUtils.findAvailableTcpPort();

    static final Network CONTAINER_NETWORK = Network.newNetwork();

    private static final String AWS_REGION = "eu-west-2";

    static final LocalStackContainer LOCAL_STACK_CONTAINER =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices("s3", "sqs")
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("localstack")
            .withEnv("DEFAULT_REGION", AWS_REGION)
            .withEnv("AWS_DEFAULT_REGION", AWS_REGION);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
        new GenericContainer<>(DockerImageName.parse("redis:7.2.3-alpine3.18"))
            .withExposedPorts(6379)
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("redis")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));

    private static final String CDP_UPLOADER_IMAGE = "defradigital/cdp-uploader:latest";

    @SuppressWarnings("resource")
    static final GenericContainer<?> CDP_UPLOADER_CONTAINER =
        new GenericContainer<>(DockerImageName.parse(CDP_UPLOADER_IMAGE))
            .withExposedPorts(3000)
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("cdp-uploader")
            .withEnv("PORT", "3000")
            .withEnv("NODE_ENV", "development")
            .withEnv("REDIS_HOST", "redis")
            .withEnv("USE_SINGLE_INSTANCE_CACHE", "true")
            .withEnv("MOCK_VIRUS_SCAN_ENABLED", "true")
            .withEnv("MOCK_VIRUS_RESULT_DELAY", "0")
            // Each SQS listener long-polls for up to its waitTimeSeconds. Defaults are 20s for
            // scan-results and the callback queue, which makes the full upload→scan→callback
            // round-trip take 20s+ even when results are immediate. Crank everything down for IT.
            .withEnv("SQS_SCAN_RESULTS_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_SCAN_RESULTS_CALLBACK_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_DOWNLOAD_REQUESTS_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_MOCK_CLAMAV_WAIT_TIME_SECONDS", "1")
            .withEnv("S3_ENDPOINT", "http://localstack:4566")
            .withEnv("AWS_REGION", AWS_REGION)
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("CONSUMER_BUCKETS", DOCUMENTS_BUCKET + "," + QUARANTINE_BUCKET)
            .waitingFor(Wait.forHttp("/health").forPort(3000)
                .withStartupTimeout(Duration.ofSeconds(60)));

    private static final S3Client localStackS3Client;
    private static final SqsClient localStackSqsClient;

    static {
        Startables.deepStart(LOCAL_STACK_CONTAINER, REDIS_CONTAINER).join();

        // Resolved at runtime — both env vars need LocalStack's container-internal endpoint.
        CDP_UPLOADER_CONTAINER.withEnv("SQS_ENDPOINT", "http://localstack:4566");
        CDP_UPLOADER_CONTAINER.withEnv(
            "SQS_SCAN_RESULTS_CALLBACK",
            "http://localstack:4566/000000000000/" + SCAN_RESULTS_CALLBACK_QUEUE);

        // Region must match cdp-uploader's AWS_REGION env var: SQS queues are scoped per region
        // even in LocalStack, so a queue created via us-east-1 (the SDK's default) is invisible
        // to a client signing as eu-west-2.
        localStackS3Client = S3Client.builder()
            .endpointOverride(LOCAL_STACK_CONTAINER.getEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    LOCAL_STACK_CONTAINER.getAccessKey(),
                    LOCAL_STACK_CONTAINER.getSecretKey())))
            .region(Region.of(AWS_REGION))
            .build();

        localStackSqsClient = SqsClient.builder()
            .endpointOverride(LOCAL_STACK_CONTAINER.getEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    LOCAL_STACK_CONTAINER.getAccessKey(),
                    LOCAL_STACK_CONTAINER.getSecretKey())))
            .region(Region.of(AWS_REGION))
            .build();

        localStackS3Client.createBucket(CreateBucketRequest.builder().bucket(DOCUMENTS_BUCKET).build());
        localStackS3Client.createBucket(CreateBucketRequest.builder().bucket(QUARANTINE_BUCKET).build());

        localStackSqsClient.createQueue(CreateQueueRequest.builder().queueName(MOCK_CLAMAV_QUEUE).build());
        localStackSqsClient.createQueue(CreateQueueRequest.builder().queueName(SCAN_RESULTS_QUEUE).build());
        localStackSqsClient.createQueue(CreateQueueRequest.builder().queueName(DOWNLOAD_REQUESTS_QUEUE).build());
        localStackSqsClient.createQueue(CreateQueueRequest.builder()
            .queueName(SCAN_RESULTS_CALLBACK_QUEUE)
            .attributes(java.util.Map.of(
                QueueAttributeName.FIFO_QUEUE, "true",
                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
            .build());

        // Wire the quarantine bucket to fan ObjectCreated events to mock-clamav so cdp-uploader's
        // mock scanner sees uploads. LocalStack uses the literal AWS account id 000000000000.
        localStackS3Client.putBucketNotificationConfiguration(
            PutBucketNotificationConfigurationRequest.builder()
                .bucket(QUARANTINE_BUCKET)
                .notificationConfiguration(NotificationConfiguration.builder()
                    .queueConfigurations(QueueConfiguration.builder()
                        .queueArn("arn:aws:sqs:" + AWS_REGION
                            + ":000000000000:" + MOCK_CLAMAV_QUEUE)
                        .events(Event.S3_OBJECT_CREATED)
                        .build())
                    .build())
                .build());

        // Stream cdp-uploader + LocalStack logs into the test output so failed scans aren't a
        // black box.
        CDP_UPLOADER_CONTAINER.withLogConsumer(
            new Slf4jLogConsumer(LoggerFactory.getLogger("cdp-uploader")));
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
        log.info("cdp-uploader started on port {}", CDP_UPLOADER_CONTAINER.getMappedPort(3000));
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
                + ":" + CDP_UPLOADER_CONTAINER.getMappedPort(3000));

        registry.add("app.aws.endpoint-override",
            () -> LOCAL_STACK_CONTAINER.getEndpoint().toString());
        registry.add("aws.region", LOCAL_STACK_CONTAINER::getRegion);
        registry.add("app.aws.access-key-id", LOCAL_STACK_CONTAINER::getAccessKey);
        registry.add("app.aws.secret-access-key", LOCAL_STACK_CONTAINER::getSecretKey);

        registry.add("cdp.s3.documents-bucket", () -> DOCUMENTS_BUCKET);

        registry.add("aws.sts.token.audience", () -> "test-audience");
        registry.add("aws.sts.token.expiration", () -> "3600");
    }

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @LocalServerPort
    int localServerPort;

    @BeforeEach
    void setUpDocuments() {
        accompanyingDocumentRepository.deleteAll();
        assertThat(localServerPort).isEqualTo(BACKEND_PORT); // sanity: DEFINED_PORT honoured
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
        String uploadId = initiateAndGetUploadId();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");

        uploadFileViaCdpUploader(uploadId, "clean.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(uploadId);
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.filename()).isEqualTo("clean.pdf");
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.fileStatus()).isEqualTo(FileStatus.COMPLETE);
        assertThat(file.contentLength()).isEqualTo(pdfBytes.length);
        assertThat(file.s3Key()).isNotBlank();
        assertThat(file.s3Bucket()).isEqualTo(DOCUMENTS_BUCKET);
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
        String uploadId = initiateAndGetUploadId();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");

        uploadFileViaCdpUploader(uploadId, "virus.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(uploadId);
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
        String uploadId = initiateAndGetUploadId();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");
        uploadFileViaCdpUploader(uploadId, "clean.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(uploadId);
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);

        EntityExchangeResult<byte[]> result = webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId + "/file")
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
        String uploadId = initiateAndGetUploadId();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");
        uploadFileViaCdpUploader(uploadId, "virus.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(uploadId);
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);

        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId + "/file")
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
        String uploadId = initiateAndGetUploadId();
        byte[] pdfBytes = loadFixtureAsBytes("fixtures/test-document.pdf");
        uploadFileViaCdpUploader(uploadId, "clean.pdf", pdfBytes, "application/pdf");

        AccompanyingDocument persisted = awaitScanCallback(uploadId);
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        String s3Key = persisted.getFiles().get(0).s3Key();

        localStackS3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(DOCUMENTS_BUCKET)
            .key(s3Key)
            .build());

        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId + "/file")
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
        return body.uploadId();
    }

    /**
     * POSTs a multipart {@code file} part to cdp-uploader's {@code /upload-and-scan/{uploadId}}
     * endpoint. cdp-uploader stores the bytes in the quarantine bucket and returns 302 to the
     * configured redirect path; we don't follow the redirect (it would land on a 404).
     */
    private void uploadFileViaCdpUploader(
        String uploadId, String filename, byte[] bytes, String contentType) {

        String cdpUploaderBaseUrl = "http://" + CDP_UPLOADER_CONTAINER.getHost()
            + ":" + CDP_UPLOADER_CONTAINER.getMappedPort(3000);

        MultiValueMap<String, HttpEntity<?>> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        partHeaders.setContentDisposition(ContentDisposition.formData()
            .name("file").filename(filename).build());
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, partHeaders));

        WebClient.builder()
            .baseUrl(cdpUploaderBaseUrl)
            .build()
            .post()
            .uri("/upload-and-scan/" + uploadId)
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
