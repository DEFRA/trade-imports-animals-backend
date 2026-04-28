package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentDto;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentListResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentUploadResponse;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.FileStatus;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;

/**
 * Integration tests for the accompanying document upload flow.
 *
 * <p>Covers: initiate → MongoDB PENDING record, scan callback (complete and rejected) →
 * MongoDB status update, list/get endpoints, file download from LocalStack S3, and 404 error paths.
 *
 * <p>Infrastructure:
 * <ul>
 *   <li>MongoDB — real Testcontainer from {@link IntegrationBase}
 *   <li>cdp-uploader — real {@code defradigital/cdp-uploader} Testcontainer
 *   <li>Redis — real Testcontainer (required by cdp-uploader)
 *   <li>S3 — LocalStack Testcontainer started in this class
 * </ul>
 *
 * <p>Scan callbacks are exercised by posting fixture payloads directly to the backend's
 * {@code /document-uploads/{uploadId}/scan-results} endpoint; the tests do not wait for
 * cdp-uploader to call back autonomously.
 */
@Slf4j
class DocumentIT extends IntegrationBase {

    // ---------------------------------------------------------------------------
    // Constants — these match values embedded in the fixture JSON files.
    // FILE_ID_FROM_FIXTURE and S3_KEY_FROM_FIXTURE come from cdp-scan-callback-complete.json
    // and are independent of the uploadId assigned by cdp-uploader at initiation time.
    // ---------------------------------------------------------------------------
    private static final String FILE_ID_FROM_FIXTURE = "c1ec2432-54ed-4153-bd6f-d69a35f598f4";
    private static final String S3_KEY_FROM_FIXTURE =
        "44f1a20e-0a27-4d61-9aa7-7afa7fb82d85/" + FILE_ID_FROM_FIXTURE;

    private static final String DOCUMENTS_BUCKET = "trade-imports-animals-documents";
    private static final String NOTIFICATION_REF = "DRAFT.IMP.2025.IT001";

    // ---------------------------------------------------------------------------
    // Shared Docker network — allows cdp-uploader to reach Redis and LocalStack
    // using stable container-alias hostnames.
    // ---------------------------------------------------------------------------
    static final Network CONTAINER_NETWORK = Network.newNetwork();

    // ---------------------------------------------------------------------------
    // LocalStack Testcontainer for S3
    // ---------------------------------------------------------------------------
    static final LocalStackContainer LOCAL_STACK_CONTAINER =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices(Service.S3)
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("localstack");

    // ---------------------------------------------------------------------------
    // Redis Testcontainer — required by cdp-uploader for session state
    // ---------------------------------------------------------------------------
    // @SuppressWarnings("resource"): GenericContainer is Closeable, but this is an intentionally
    // long-lived static container — lifecycle is managed by Testcontainers' Ryuk reaper.
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
        new GenericContainer<>(DockerImageName.parse("redis:7.2.3-alpine3.18"))
            .withExposedPorts(6379)
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("redis")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));

    // ---------------------------------------------------------------------------
    // Real cdp-uploader Testcontainer
    // ---------------------------------------------------------------------------
    // @SuppressWarnings("resource"): GenericContainer is Closeable, but this is an intentionally
    // long-lived static container — lifecycle is managed by Testcontainers' Ryuk reaper.
    // Intentionally unpinned: latest catches cdp-uploader contract changes early in the absence
    // of contract testing. Pin this if/when contract tests are introduced.
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
            .withEnv("MOCK_VIRUS_RESULT_DELAY", "3")
            .withEnv("S3_ENDPOINT", "http://localstack:4566")
            .withEnv("AWS_REGION", "eu-west-2")
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("CONSUMER_BUCKETS", "trade-imports-animals-documents,cdp-uploader-quarantine")
            .waitingFor(Wait.forHttp("/health").forPort(3000)
                .withStartupTimeout(Duration.ofSeconds(60)));

    // S3 client wired to LocalStack for test setup (pre-creating buckets / uploading objects)
    private static final S3Client localStackS3Client;

    static {
        // Start LocalStack and Redis first; cdp-uploader depends on both.
        Startables.deepStart(LOCAL_STACK_CONTAINER, REDIS_CONTAINER).join();

        // SQS_ENDPOINT must reference LocalStack — resolve after LocalStack starts.
        // LocalStack exposes a single port (4566) for all services; use the container-alias
        // hostname "localstack" so cdp-uploader (running inside Docker) can reach it.
        CDP_UPLOADER_CONTAINER.withEnv("SQS_ENDPOINT", "http://localstack:4566");

        Startables.deepStart(CDP_UPLOADER_CONTAINER).join();

        localStackS3Client = S3Client.builder()
            .endpointOverride(LOCAL_STACK_CONTAINER.getEndpointOverride(Service.S3))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    LOCAL_STACK_CONTAINER.getAccessKey(),
                    LOCAL_STACK_CONTAINER.getSecretKey())))
            .region(Region.of(LOCAL_STACK_CONTAINER.getRegion()))
            .build();

        // Pre-create the buckets needed by the application and cdp-uploader
        localStackS3Client.createBucket(
            CreateBucketRequest.builder().bucket(DOCUMENTS_BUCKET).build());
        localStackS3Client.createBucket(
            CreateBucketRequest.builder().bucket("cdp-uploader-quarantine").build());

        log.info("LocalStack S3 started; endpoint={}; buckets created",
            LOCAL_STACK_CONTAINER.getEndpoint());
        log.info("Redis started on port {}", REDIS_CONTAINER.getMappedPort(6379));
        log.info("cdp-uploader started on port {}", CDP_UPLOADER_CONTAINER.getMappedPort(3000));
    }

    @DynamicPropertySource
    static void registerDocumentITProperties(DynamicPropertyRegistry registry) {
        // Point cdp-uploader client at the real container
        registry.add("cdp.uploader.base-url",
            () -> "http://" + CDP_UPLOADER_CONTAINER.getHost()
                + ":" + CDP_UPLOADER_CONTAINER.getMappedPort(3000));

        // Point S3Client at LocalStack
        registry.add("app.aws.endpoint-override",
            () -> LOCAL_STACK_CONTAINER.getEndpointOverride(Service.S3).toString());

        // LocalStack credentials / region
        registry.add("aws.region", LOCAL_STACK_CONTAINER::getRegion);
        registry.add("app.aws.access-key-id", LOCAL_STACK_CONTAINER::getAccessKey);
        registry.add("app.aws.secret-access-key", LOCAL_STACK_CONTAINER::getSecretKey);

        // CDP documents bucket name (must match what was pre-created)
        registry.add("cdp.s3.documents-bucket", () -> DOCUMENTS_BUCKET);

        // Disable STS / EMF in tests (no real AWS)
        registry.add("aws.sts.token.audience", () -> "test-audience");
        registry.add("aws.sts.token.expiration", () -> "3600");
    }

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @BeforeEach
    void setUpDocuments() {
        accompanyingDocumentRepository.deleteAll();
    }

    // ---------------------------------------------------------------------------
    // Test: initiate upload — happy path
    // ---------------------------------------------------------------------------

    /**
     * Full initiate flow: POST to /notifications/{ref}/document-uploads calls the real
     * cdp-uploader /initiate, asserts 201 Created with correct body shape, and verifies that a
     * PENDING AccompanyingDocument was persisted in MongoDB.
     */
    @Test
    void initiate_shouldReturn201AndPersistPendingDocument() throws IOException {
        // Act — POST to our backend which calls the real cdp-uploader internally
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234","dateOfIssue":"2026-01-15"}
                """)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        // Assert response body
        DocumentUploadResponse responseBody = result.getResponseBody();
        assertThat(responseBody).isNotNull();
        String uploadId = responseBody.uploadId();
        assertThat(uploadId).isNotBlank();

        // Assert Location header contains the assigned uploadId
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
            .endsWith("/document-uploads/" + uploadId);

        // Assert the upload URL returned by the real cdp-uploader uses the same uploadId
        assertThat(responseBody.uploadUrl()).contains("/upload-and-scan/" + uploadId);

        // Assert MongoDB persisted a PENDING record
        List<AccompanyingDocument> persisted =
            accompanyingDocumentRepository.findAllByNotificationReferenceNumber(NOTIFICATION_REF);
        assertThat(persisted).hasSize(1);
        AccompanyingDocument doc = persisted.get(0);
        assertThat(doc.getUploadId()).isEqualTo(uploadId);
        assertThat(doc.getNotificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(doc.getScanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(doc.getDocumentType()).isNotNull();
        assertThat(doc.getDocumentReference()).isEqualTo("UK/GB/2026/001234");
        assertThat(doc.getFiles()).isEmpty();
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getCreated()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Test: scan callback — complete (clean file)
    // ---------------------------------------------------------------------------

    /**
     * Scan callback flow for a clean file: POST to /document-uploads/{id}/scan-results with the
     * complete fixture → asserts 204 and that MongoDB document has scanStatus=COMPLETE and the
     * files list populated with the expected file metadata.
     */
    @Test
    void scanResult_complete_shouldUpdateStatusAndPopulateFiles() throws IOException {
        // Arrange — initiate via real cdp-uploader to get a PENDING record
        String uploadId = initiateAndGetUploadId();

        // Read the complete callback fixture
        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");

        // Act — simulate the callback from cdp-uploader
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert MongoDB updated
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.filename()).isEqualTo("test.pdf");
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.fileStatus()).isEqualTo(FileStatus.COMPLETE);
        assertThat(file.contentLength()).isEqualTo(22L);
        assertThat(file.s3Key()).isEqualTo(S3_KEY_FROM_FIXTURE);
        assertThat(file.detectedContentType()).isEqualTo("application/pdf");
        assertThat(file.checksumSha256())
            .isEqualTo("/SxqL+N296KUYPUFOkjpBtHEck5oMdfvh/HDZDEetbA=");
    }

    // ---------------------------------------------------------------------------
    // Test: scan callback — rejected (virus detected)
    // ---------------------------------------------------------------------------

    /**
     * Scan callback for a rejected file: POST to /document-uploads/{id}/scan-results with the
     * rejected fixture → asserts 204 and that MongoDB document has scanStatus=REJECTED, s3Key is
     * null, and fileStatus=REJECTED.
     */
    @Test
    void scanResult_rejected_shouldUpdateStatusToRejected() throws IOException {
        // Arrange
        String uploadId = initiateAndGetUploadId();

        String rejectedPayload = loadFixtureAsString("fixtures/cdp-scan-callback-rejected.json");

        // Act
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(rejectedPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.filename()).isEqualTo("eicar.pdf");
        assertThat(file.fileStatus()).isEqualTo(FileStatus.REJECTED);
        assertThat(file.s3Key()).isNull();
    }

    // ---------------------------------------------------------------------------
    // Test: list documents
    // ---------------------------------------------------------------------------

    /**
     * GET /notifications/{ref}/document-uploads returns the saved document for the given
     * notification reference.
     */
    @Test
    void listDocuments_shouldReturnDocumentsForNotification() throws IOException {
        // Arrange — initiate so we have a document in the DB
        String uploadId = initiateAndGetUploadId();

        // Act
        EntityExchangeResult<DocumentListResponse> result = webClient("NoAuth")
            .get()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .exchange()
            .expectStatus().isOk()
            .expectBody(DocumentListResponse.class)
            .returnResult();

        // Assert
        DocumentListResponse listResponse = result.getResponseBody();
        assertThat(listResponse).isNotNull();
        assertThat(listResponse.items()).hasSize(1);
        AccompanyingDocumentDto item = listResponse.items().get(0);
        assertThat(item.uploadId()).isEqualTo(uploadId);
        assertThat(item.notificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(item.scanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(item.id()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Test: get by upload ID
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id} returns 200 with the correct DTO for a known upload ID.
     */
    @Test
    void getByUploadId_shouldReturn200WithCorrectDto() throws IOException {
        // Arrange
        String uploadId = initiateAndGetUploadId();

        // Act
        EntityExchangeResult<AccompanyingDocumentDto> result = webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AccompanyingDocumentDto.class)
            .returnResult();

        // Assert
        AccompanyingDocumentDto dto = result.getResponseBody();
        assertThat(dto).isNotNull();
        assertThat(dto.uploadId()).isEqualTo(uploadId);
        assertThat(dto.notificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(dto.scanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(dto.id()).isNotNull();
        assertThat(dto.created()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Test: download file from S3
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id}/file — uploads a test file to LocalStack S3 at the
     * fixture s3Key, then asserts that the endpoint streams the file bytes and returns correct
     * Content-Type and Content-Disposition headers.
     */
    @Test
    void downloadFile_shouldStreamFileFromS3WithCorrectHeaders() throws IOException {
        // Arrange — initiate to get a PENDING record, then simulate a complete scan callback
        String uploadId = initiateAndGetUploadId();

        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNoContent();

        // Pre-load the test file into LocalStack S3 at the s3Key from the fixture
        byte[] testFileContent = loadFixtureAsBytes("fixtures/test-document.pdf");
        localStackS3Client.putObject(
            PutObjectRequest.builder()
                .bucket(DOCUMENTS_BUCKET)
                .key(S3_KEY_FROM_FIXTURE)
                .contentType("application/pdf")
                .build(),
            RequestBody.fromBytes(testFileContent));

        // Act
        EntityExchangeResult<byte[]> result = webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId + "/file")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_PDF)
            .expectHeader().valueMatches(
                HttpHeaders.CONTENT_DISPOSITION,
                ".*filename\\*=UTF-8''test\\.pdf.*")
            .expectBody(byte[].class)
            .returnResult();

        // Assert streamed bytes match what we uploaded
        byte[] responseBytes = result.getResponseBody();
        assertThat(responseBytes).isNotNull();
        assertThat(responseBytes).isEqualTo(testFileContent);
    }

    // ---------------------------------------------------------------------------
    // Test: 404 path — unknown upload ID for GET
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id} with an unknown ID returns 404 problem+json.
     */
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

    /**
     * GET /document-uploads/{id}/file where the upload session has no files (scan callback not yet
     * received) returns 404 problem+json.
     */
    @Test
    void downloadFile_noFilesPresent_shouldReturn404() {
        // Arrange — initiate only; do not post a scan callback, so the files list is empty
        String uploadId = initiateAndGetUploadId();

        // Act — attempt to download before any scan callback has populated the files list
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
    // Test: scan callback for unknown uploadId → 404
    // ---------------------------------------------------------------------------

    /**
     * POST to /document-uploads/{id}/scan-results with an uploadId that does not exist in MongoDB
     * returns 404 with a problem+json body containing status=404.
     */
    @Test
    void scanResult_unknownUploadId_shouldReturn404() throws IOException {
        // Arrange — no document persisted for this uploadId
        String unknownUploadId = "non-existent-upload-id";
        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");

        // Act / Assert
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + unknownUploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404);
    }

    // ---------------------------------------------------------------------------
    // Test: scan callback via "pending" alias path — resolves by notificationReferenceNumber
    // ---------------------------------------------------------------------------

    /**
     * POST to /document-uploads/pending/scan-results with notificationReferenceNumber in metadata:
     * the production route used by cdp-uploader. The service must resolve the PENDING document by
     * notification reference and update its scan status to COMPLETE.
     */
    @Test
    void scanResult_viaPendingAlias_shouldResolveByNotificationRef() throws IOException {
        // Arrange — initiate to create a PENDING record with the known notification ref
        initiateAndGetUploadId();

        String pendingPayload = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": 0,
              "metadata": {
                "notificationReferenceNumber": "%s"
              },
              "form": {
                "file": {
                  "fileId": "%s",
                  "filename": "test.pdf",
                  "contentType": "application/pdf",
                  "fileStatus": "complete",
                  "contentLength": 22,
                  "checksumSha256": "/SxqL+N296KUYPUFOkjpBtHEck5oMdfvh/HDZDEetbA=",
                  "detectedContentType": "application/pdf",
                  "s3Key": "%s"
                }
              }
            }
            """.formatted(NOTIFICATION_REF, FILE_ID_FROM_FIXTURE, S3_KEY_FROM_FIXTURE);

        // Act — post to the "pending" alias path, as cdp-uploader does in production
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/pending/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(pendingPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert MongoDB updated via the alias lookup
        List<AccompanyingDocument> docs =
            accompanyingDocumentRepository.findAllByNotificationReferenceNumber(NOTIFICATION_REF);
        assertThat(docs).hasSize(1);
        AccompanyingDocument persisted = docs.get(0);
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        assertThat(persisted.getFiles()).hasSize(1);
        assertThat(persisted.getFiles().get(0).filename()).isEqualTo("test.pdf");
        assertThat(persisted.getFiles().get(0).s3Key()).isEqualTo(S3_KEY_FROM_FIXTURE);
    }

    // ---------------------------------------------------------------------------
    // Test: multi-file scan callback with mixed COMPLETE + REJECTED
    // ---------------------------------------------------------------------------

    /**
     * POST a scan callback with two files — one COMPLETE (s3Key populated) and one REJECTED
     * (s3Key null). The aggregate scanStatus should be REJECTED because numberOfRejectedFiles > 0.
     * Both file entries should be stored with the correct per-file fileStatus and s3Key values.
     */
    @Test
    void scanResult_mixedCompleteAndRejected_shouldSetAggregateStatusToRejected() throws IOException {
        // Arrange — initiate to get a PENDING document
        String uploadId = initiateAndGetUploadId();

        String mixedPayload = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": 1,
              "metadata": {},
              "form": {
                "cleanFile": {
                  "fileId": "file-clean-001",
                  "filename": "clean.pdf",
                  "contentType": "application/pdf",
                  "fileStatus": "complete",
                  "contentLength": 1024,
                  "checksumSha256": "abc123",
                  "detectedContentType": "application/pdf",
                  "s3Key": "%s/file-clean-001",
                  "s3Bucket": "%s",
                  "hasError": false,
                  "errorMessage": null
                },
                "virusFile": {
                  "fileId": "file-virus-002",
                  "filename": "eicar.pdf",
                  "contentType": "application/pdf",
                  "fileStatus": "rejected",
                  "contentLength": 68,
                  "checksumSha256": "def456",
                  "detectedContentType": "application/pdf",
                  "s3Key": null,
                  "s3Bucket": null,
                  "hasError": true,
                  "errorMessage": "Virus detected"
                }
              }
            }
            """.formatted(uploadId, DOCUMENTS_BUCKET);

        // Act
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mixedPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert MongoDB state
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(persisted.getFiles()).hasSize(2);

        var cleanFile = persisted.getFiles().stream()
            .filter(f -> FileStatus.COMPLETE.equals(f.fileStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("clean file not found"));
        assertThat(cleanFile.s3Key()).isNotNull();
        assertThat(cleanFile.s3Key()).isEqualTo(uploadId + "/file-clean-001");

        var virusFile = persisted.getFiles().stream()
            .filter(f -> FileStatus.REJECTED.equals(f.fileStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("virus file not found"));
        assertThat(virusFile.s3Key()).isNull();
    }

    // ---------------------------------------------------------------------------
    // Test: dateOfIssue round-trip
    // ---------------------------------------------------------------------------

    /**
     * Initiates a document upload with dateOfIssue="2026-01-15", then reads the persisted entity
     * from MongoDB and asserts the dateOfIssue Instant represents midnight UTC on that date.
     */
    @Test
    void initiate_withDateOfIssue_shouldPersistDateOfIssueAsInstant() throws IOException {
        // Act — include dateOfIssue in the request body
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234","dateOfIssue":"2026-01-15"}
                """)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        String uploadId = result.getResponseBody().uploadId();

        // Assert MongoDB
        AccompanyingDocument doc =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(doc.getDateOfIssue()).isNotNull();
        assertThat(doc.getDateOfIssue())
            .isEqualTo(java.time.Instant.parse("2026-01-15T00:00:00Z"));
    }

    // ---------------------------------------------------------------------------
    // Test: null numberOfRejectedFiles in scan callback → REJECTED
    // ---------------------------------------------------------------------------

    /**
     * POST a scan callback where numberOfRejectedFiles is null (field omitted). The production
     * code condition (null != null && null == 0) evaluates false, so scanStatus must be REJECTED.
     * Guards against a silent data-loss bug if cdp-uploader omits this field.
     */
    @Test
    void scanResult_nullNumberOfRejectedFiles_shouldSetStatusToRejected() throws IOException {
        // Arrange
        String uploadId = initiateAndGetUploadId();

        String payloadWithNullRejectedCount = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": null,
              "metadata": {},
              "form": {
                "file": {
                  "fileId": "%s",
                  "filename": "test.pdf",
                  "contentType": "application/pdf",
                  "fileStatus": "complete",
                  "contentLength": 22,
                  "checksumSha256": "abc123",
                  "detectedContentType": "application/pdf",
                  "s3Key": "%s"
                }
              }
            }
            """.formatted(FILE_ID_FROM_FIXTURE, S3_KEY_FROM_FIXTURE);

        // Act
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payloadWithNullRejectedCount)
            .exchange()
            .expectStatus().isNoContent();

        // Assert — null numberOfRejectedFiles must not be treated as COMPLETE
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
    }

    // ---------------------------------------------------------------------------
    // Test: download rejected file (null s3Key) → 404
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id}/file where the file was rejected (s3Key is null).
     * The endpoint must return 404 before attempting any S3 call — passing null to S3 would
     * cause an SDK error and expose internals.
     */
    @Test
    void downloadFile_rejectedFile_shouldReturn404() throws IOException {
        // Arrange — initiate then simulate a rejected scan callback
        String uploadId = initiateAndGetUploadId();

        String rejectedPayload = loadFixtureAsString("fixtures/cdp-scan-callback-rejected.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(rejectedPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Act — attempt to download the rejected file
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
     * GET /document-uploads/{id}/file where the file exists in MongoDB (COMPLETE) but
     * has no corresponding object in S3. Verifies the endpoint returns 500 with a structured error
     * body rather than hanging or returning a partial response.
     */
    @Test
    void downloadFile_s3ObjectMissing_shouldReturn500() throws IOException {
        // Arrange — initiate, complete the scan (registers file metadata in MongoDB)
        String uploadId = initiateAndGetUploadId();

        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNoContent();

        // Deliberately do NOT put any object in LocalStack S3 — the key doesn't exist

        // Act — request the file; S3 will return NoSuchKey
        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + uploadId + "/file")
            .exchange()
            .expectStatus().is5xxServerError();
    }

    // ---------------------------------------------------------------------------
    // Tests: DELETE /document-uploads/{upload-id} — admin secret protection
    // ---------------------------------------------------------------------------

    private static final String ADMIN_SECRET_HEADER = "Trade-Imports-Animals-Admin-Secret";
    private static final String VALID_ADMIN_SECRET = "test-admin-secret";

    /**
     * DELETE /document-uploads/{id} without the admin secret header must return 401.
     */
    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsMissing() throws IOException {
        String uploadId = initiateAndGetUploadId();

        webClient("NoAuth")
            .delete()
            .uri("/document-uploads/" + uploadId)
            .exchange()
            .expectStatus().isUnauthorized();

        // Document must not have been deleted
        assertThat(accompanyingDocumentRepository.findByUploadId(uploadId)).isPresent();
    }

    /**
     * DELETE /document-uploads/{id} with a wrong admin secret must return 401.
     */
    @Test
    void delete_shouldReturn401_whenAdminSecretHeaderIsIncorrect() throws IOException {
        String uploadId = initiateAndGetUploadId();

        webClient("NoAuth")
            .delete()
            .uri("/document-uploads/" + uploadId)
            .header(ADMIN_SECRET_HEADER, "wrong-secret")
            .exchange()
            .expectStatus().isUnauthorized();

        // Document must not have been deleted
        assertThat(accompanyingDocumentRepository.findByUploadId(uploadId)).isPresent();
    }

    /**
     * DELETE /document-uploads/{id} with the correct admin secret must return 204 and remove the
     * document from MongoDB.
     */
    @Test
    void delete_shouldReturn204AndRemoveDocument_whenAdminSecretIsCorrect() throws IOException {
        String uploadId = initiateAndGetUploadId();

        webClient("NoAuth")
            .delete()
            .uri("/document-uploads/" + uploadId)
            .header(ADMIN_SECRET_HEADER, VALID_ADMIN_SECRET)
            .exchange()
            .expectStatus().isNoContent();

        assertThat(accompanyingDocumentRepository.findByUploadId(uploadId)).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Calls the backend's initiate endpoint (which in turn calls the real cdp-uploader) and
     * returns the dynamically-assigned uploadId from the response. A PENDING
     * {@link AccompanyingDocument} is persisted in MongoDB as a side-effect.
     */
    private String initiateAndGetUploadId() {
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234","dateOfIssue":"2026-01-15"}
                """)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        DocumentUploadResponse body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.uploadId()).isNotBlank();
        return body.uploadId();
    }

}
