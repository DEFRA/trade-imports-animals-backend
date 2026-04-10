package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
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
 *   <li>cdp-uploader — stubbed via MockServer (from {@link IntegrationBase})
 *   <li>S3 — LocalStack Testcontainer started in this class
 * </ul>
 */
@Slf4j
class DocumentIT extends IntegrationBase {

    // ---------------------------------------------------------------------------
    // Constants matching the fixture files
    // ---------------------------------------------------------------------------
    private static final String UPLOAD_ID_FROM_FIXTURE = "44f1a20e-0a27-4d61-9aa7-7afa7fb82d85";
    private static final String FILE_ID_FROM_FIXTURE = "c1ec2432-54ed-4153-bd6f-d69a35f598f4";
    private static final String S3_KEY_FROM_FIXTURE =
        UPLOAD_ID_FROM_FIXTURE + "/" + FILE_ID_FROM_FIXTURE;

    private static final String DOCUMENTS_BUCKET = "trade-imports-animals-documents";
    private static final String NOTIFICATION_REF = "DRAFT.IMP.2025.IT001";

    // ---------------------------------------------------------------------------
    // LocalStack Testcontainer for S3
    // ---------------------------------------------------------------------------
    static final LocalStackContainer LOCAL_STACK_CONTAINER =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices(Service.S3);

    // S3 client wired to LocalStack for test setup (pre-creating buckets / uploading objects)
    private static S3Client localStackS3Client;

    static {
        Startables.deepStart(LOCAL_STACK_CONTAINER).join();

        localStackS3Client = S3Client.builder()
            .endpointOverride(LOCAL_STACK_CONTAINER.getEndpointOverride(Service.S3))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    LOCAL_STACK_CONTAINER.getAccessKey(),
                    LOCAL_STACK_CONTAINER.getSecretKey())))
            .region(Region.of(LOCAL_STACK_CONTAINER.getRegion()))
            .build();

        // Pre-create the buckets needed by the application
        localStackS3Client.createBucket(
            CreateBucketRequest.builder().bucket(DOCUMENTS_BUCKET).build());
        localStackS3Client.createBucket(
            CreateBucketRequest.builder().bucket("cdp-uploader-quarantine").build());

        log.info("LocalStack S3 started; endpoint={}; buckets created",
            LOCAL_STACK_CONTAINER.getEndpoint());
    }

    @DynamicPropertySource
    static void registerDocumentITProperties(DynamicPropertyRegistry registry) {
        // Point cdp-uploader client at MockServer
        registry.add("cdp.uploader.base-url",
            () -> MOCK_SERVER_CONTAINER.getEndpoint());

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
     * Full initiate flow: POST to /notifications/{ref}/document-uploads stubs cdp-uploader
     * /initiate via MockServer, asserts 201 Created with correct body, and verifies that a PENDING
     * AccompanyingDocument was persisted in MongoDB.
     */
    @Test
    void initiate_shouldReturn201AndPersistPendingDocument() throws IOException {
        // Arrange — stub cdp-uploader /initiate with the captured fixture response
        usingStub()
            .when(request().withMethod("POST").withPath("/initiate"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                    .withBody(getJsonFromFile("fixtures/cdp-initiate-response.json")));

        // Act
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234"}
                """)
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueMatches(
                HttpHeaders.LOCATION, ".*/document-uploads/" + UPLOAD_ID_FROM_FIXTURE)
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        // Assert response body
        DocumentUploadResponse responseBody = result.getResponseBody();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody.uploadId()).isEqualTo(UPLOAD_ID_FROM_FIXTURE);
        assertThat(responseBody.uploadUrl())
            .isEqualTo("http://localhost:7337/upload-and-scan/" + UPLOAD_ID_FROM_FIXTURE);

        // Assert MockServer received the right request
        usingStub().verify(
            request()
                .withMethod("POST")
                .withPath("/initiate")
                .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON));

        // Assert MongoDB persisted a PENDING record
        List<AccompanyingDocument> persisted =
            accompanyingDocumentRepository.findAllByNotificationReferenceNumber(NOTIFICATION_REF);
        assertThat(persisted).hasSize(1);
        AccompanyingDocument doc = persisted.get(0);
        assertThat(doc.getUploadId()).isEqualTo(UPLOAD_ID_FROM_FIXTURE);
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
        // Arrange — persist a PENDING document first
        stubCdpUploaderAndInitiate();

        // Read the complete callback fixture
        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");

        // Act — simulate the callback from cdp-uploader
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert MongoDB updated
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(UPLOAD_ID_FROM_FIXTURE).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.fileId()).isEqualTo(FILE_ID_FROM_FIXTURE);
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
        stubCdpUploaderAndInitiate();

        String rejectedPayload = loadFixtureAsString("fixtures/cdp-scan-callback-rejected.json");

        // Act
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(rejectedPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(UPLOAD_ID_FROM_FIXTURE).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(persisted.getFiles()).hasSize(1);

        var file = persisted.getFiles().get(0);
        assertThat(file.fileId()).isEqualTo(FILE_ID_FROM_FIXTURE);
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
        stubCdpUploaderAndInitiate();

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
        assertThat(item.uploadId()).isEqualTo(UPLOAD_ID_FROM_FIXTURE);
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
        stubCdpUploaderAndInitiate();

        // Act
        EntityExchangeResult<AccompanyingDocumentDto> result = webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AccompanyingDocumentDto.class)
            .returnResult();

        // Assert
        AccompanyingDocumentDto dto = result.getResponseBody();
        assertThat(dto).isNotNull();
        assertThat(dto.uploadId()).isEqualTo(UPLOAD_ID_FROM_FIXTURE);
        assertThat(dto.notificationReferenceNumber()).isEqualTo(NOTIFICATION_REF);
        assertThat(dto.scanStatus()).isEqualTo(ScanStatus.PENDING);
        assertThat(dto.id()).isNotNull();
        assertThat(dto.version()).isNotNull();
        assertThat(dto.created()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Test: download file from S3
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id}/files/{fileId} — uploads a test file to LocalStack S3 at the
     * fixture s3Key, then asserts that the endpoint streams the file bytes and returns correct
     * Content-Type and Content-Disposition headers.
     */
    @Test
    void downloadFile_shouldStreamFileFromS3WithCorrectHeaders() throws IOException {
        // Arrange — initiate to get a PENDING record, then simulate a complete scan callback
        stubCdpUploaderAndInitiate();

        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
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
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/files/" + FILE_ID_FROM_FIXTURE)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_PDF)
            .expectHeader().valueMatches(
                HttpHeaders.CONTENT_DISPOSITION,
                ".*filename=\"test.pdf\".*")
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
     * GET /document-uploads/{id}/files/{fileId} with an unknown fileId (but valid uploadId)
     * returns 404 problem+json.
     */
    @Test
    void downloadFile_unknownFileId_shouldReturn404() throws IOException {
        // Arrange — initiate so document exists, then complete the scan
        stubCdpUploaderAndInitiate();

        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNoContent();

        // Act — request a file ID that does not exist
        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/files/non-existent-file-id")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value((String detail) ->
                assertThat(detail).contains("non-existent-file-id"));
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
        stubCdpUploaderAndInitiate();

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
            """.formatted(UPLOAD_ID_FROM_FIXTURE, DOCUMENTS_BUCKET);

        // Act
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mixedPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Assert MongoDB state
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(UPLOAD_ID_FROM_FIXTURE).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(persisted.getFiles()).hasSize(2);

        var cleanFile = persisted.getFiles().stream()
            .filter(f -> "file-clean-001".equals(f.fileId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("clean file not found"));
        assertThat(cleanFile.fileStatus()).isEqualTo(FileStatus.COMPLETE);
        assertThat(cleanFile.s3Key()).isNotNull();
        assertThat(cleanFile.s3Key()).isEqualTo(UPLOAD_ID_FROM_FIXTURE + "/file-clean-001");

        var virusFile = persisted.getFiles().stream()
            .filter(f -> "file-virus-002".equals(f.fileId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("virus file not found"));
        assertThat(virusFile.fileStatus()).isEqualTo(FileStatus.REJECTED);
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
        // Arrange — stub cdp-uploader /initiate
        usingStub()
            .when(request().withMethod("POST").withPath("/initiate"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                    .withBody(getJsonFromFile("fixtures/cdp-initiate-response.json")));

        // Act — include dateOfIssue in the request body
        webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234","dateOfIssue":"2026-01-15"}
                """)
            .exchange()
            .expectStatus().isCreated();

        // Assert MongoDB
        AccompanyingDocument doc =
            accompanyingDocumentRepository.findByUploadId(UPLOAD_ID_FROM_FIXTURE).orElseThrow();
        assertThat(doc.getDateOfIssue()).isNotNull();
        assertThat(doc.getDateOfIssue())
            .isEqualTo(java.time.Instant.parse("2026-01-15T00:00:00Z"));
    }

    /**
     * Initiates a document upload without dateOfIssue, then asserts the stored dateOfIssue is null.
     */
    @Test
    void initiate_withoutDateOfIssue_shouldPersistNullDateOfIssue() throws IOException {
        // Arrange — stub cdp-uploader /initiate
        usingStub()
            .when(request().withMethod("POST").withPath("/initiate"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                    .withBody(getJsonFromFile("fixtures/cdp-initiate-response.json")));

        // Act — no dateOfIssue field in the request body
        webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234"}
                """)
            .exchange()
            .expectStatus().isCreated();

        // Assert MongoDB
        AccompanyingDocument doc =
            accompanyingDocumentRepository.findByUploadId(UPLOAD_ID_FROM_FIXTURE).orElseThrow();
        assertThat(doc.getDateOfIssue()).isNull();
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
        stubCdpUploaderAndInitiate();

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
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payloadWithNullRejectedCount)
            .exchange()
            .expectStatus().isNoContent();

        // Assert — null numberOfRejectedFiles must not be treated as COMPLETE
        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(UPLOAD_ID_FROM_FIXTURE).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
    }

    // ---------------------------------------------------------------------------
    // Test: download rejected file (null s3Key) → 404
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id}/files/{fileId} where the file was rejected (s3Key is null).
     * The endpoint must return 404 before attempting any S3 call — passing null to S3 would
     * cause an SDK error and expose internals.
     */
    @Test
    void downloadFile_rejectedFile_shouldReturn404() throws IOException {
        // Arrange — initiate then simulate a rejected scan callback
        stubCdpUploaderAndInitiate();

        String rejectedPayload = loadFixtureAsString("fixtures/cdp-scan-callback-rejected.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(rejectedPayload)
            .exchange()
            .expectStatus().isNoContent();

        // Act — attempt to download the rejected file
        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/files/" + FILE_ID_FROM_FIXTURE)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404);
    }

    // ---------------------------------------------------------------------------
    // Test: S3 error during file download → 500
    // ---------------------------------------------------------------------------

    /**
     * GET /document-uploads/{id}/files/{fileId} where the file exists in MongoDB (COMPLETE) but
     * has no corresponding object in S3. Verifies the endpoint returns 500 with a structured error
     * body rather than hanging or returning a partial response.
     */
    @Test
    void downloadFile_s3ObjectMissing_shouldReturn500() throws IOException {
        // Arrange — initiate, complete the scan (registers file metadata in MongoDB)
        stubCdpUploaderAndInitiate();

        String completePayload = loadFixtureAsString("fixtures/cdp-scan-callback-complete.json");
        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNoContent();

        // Deliberately do NOT put any object in LocalStack S3 — the key doesn't exist

        // Act — request the file; S3 will return NoSuchKey
        webClient("NoAuth")
            .get()
            .uri("/document-uploads/" + UPLOAD_ID_FROM_FIXTURE + "/files/" + FILE_ID_FROM_FIXTURE)
            .exchange()
            .expectStatus().is5xxServerError();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Stubs MockServer for cdp-uploader /initiate and calls the backend initiate endpoint so that
     * a PENDING AccompanyingDocument is saved in MongoDB with the fixture uploadId.
     */
    private void stubCdpUploaderAndInitiate() throws IOException {
        usingStub()
            .when(request().withMethod("POST").withPath("/initiate"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
                    .withBody(getJsonFromFile("fixtures/cdp-initiate-response.json")));

        webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UK/GB/2026/001234"}
                """)
            .exchange()
            .expectStatus().isCreated();
    }

    /**
     * Loads a classpath fixture file as a UTF-8 String.
     */
    private String loadFixtureAsString(String classpathResource) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Fixture not found: " + classpathResource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Loads a classpath fixture file as raw bytes.
     */
    private byte[] loadFixtureAsBytes(String classpathResource) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Fixture not found: " + classpathResource);
            }
            return stream.readAllBytes();
        }
    }
}
