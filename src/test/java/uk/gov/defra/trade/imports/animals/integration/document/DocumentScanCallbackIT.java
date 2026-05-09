package uk.gov.defra.trade.imports.animals.integration.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;

/**
 * Defensive scan-callback ITs: shapes the real cdp-uploader would never produce, but our handler
 * has to cope with anyway. Direct-POST fixture pattern is the right tool here — we're exercising
 * our routing/parsing/error mapping, not cdp-uploader.
 *
 * <p>The end-to-end CLEAN/REJECTED happy paths live in {@link DocumentControllerIT} and run
 * against a real cdp-uploader scan.
 */
class DocumentScanCallbackIT extends IntegrationBase {

    private static final String NOTIFICATION_REF = "DRAFT.IMP.2025.IT-CB-001";
    private static final String DOCUMENTS_BUCKET = "trade-imports-animals-documents";
    private static final String S3_KEY_FROM_FIXTURE =
        "44f1a20e-0a27-4d61-9aa7-7afa7fb82d85/c1ec2432-54ed-4153-bd6f-d69a35f598f4";
    private static final String FILE_ID_FROM_FIXTURE = "c1ec2432-54ed-4153-bd6f-d69a35f598f4";

    /**
     * No real cdp-uploader call is exercised here, but the controller needs a non-blank
     * {@code cdp.uploader.base-url} to start the application context. Point it at a clearly
     * unreachable address so any accidental outbound call fails fast and visibly.
     */
    @DynamicPropertySource
    static void registerStubProperties(DynamicPropertyRegistry registry) {
        registry.add("cdp.uploader.base-url", () -> "http://127.0.0.1:1");
        registry.add("cdp.s3.documents-bucket", () -> DOCUMENTS_BUCKET);
        registry.add("aws.sts.token.audience", () -> "test-audience");
        registry.add("aws.sts.token.expiration", () -> "3600");
    }

    @Autowired
    private AccompanyingDocumentRepository accompanyingDocumentRepository;

    @BeforeEach
    void resetState() {
        accompanyingDocumentRepository.deleteAll();
    }

    @Test
    void scanResult_unknownCorrelationId_shouldReturn404() throws IOException {
        String unknownCorrelationId = "non-existent-correlation-id";
        String completePayload = loadCallbackFixture(
            "fixtures/cdp-scan-callback-complete.json", unknownCorrelationId);

        webClient("NoAuth")
            .post()
            .uri("/document-uploads/pending/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(completePayload)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").value((String detail) ->
                assertThat(detail).contains(unknownCorrelationId));
    }

    @Test
    void scanResult_missingCorrelationId_shouldReturn400() {
        String payloadMissingCorrelationId = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": 0,
              "metadata": {
                "notificationReferenceNumber": "%s"
              },
              "form": {}
            }
            """.formatted(NOTIFICATION_REF);

        webClient("NoAuth")
            .post()
            .uri("/document-uploads/pending/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payloadMissingCorrelationId)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.title").isEqualTo("Bad Request")
            .jsonPath("$.detail")
            .isEqualTo("Scan callback missing required correlationId in metadata");
    }

    /**
     * cdp-uploader posts to {@code /document-uploads/pending/scan-results} (the path's
     * "pending" segment is informational; identity comes from {@code metadata.correlationId}).
     */
    @Test
    void scanResult_viaPendingAlias_shouldResolveByCorrelationId() {
        AccompanyingDocument seed = persistPendingDocument();
        String correlationId = seed.getCorrelationId();
        String uploadId = seed.getUploadId();

        String pendingPayload = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": 0,
              "metadata": {
                "notificationReferenceNumber": "%s",
                "correlationId": "%s"
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
            """.formatted(NOTIFICATION_REF, correlationId, FILE_ID_FROM_FIXTURE, S3_KEY_FROM_FIXTURE);

        webClient("NoAuth")
            .post()
            .uri("/document-uploads/pending/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(pendingPayload)
            .exchange()
            .expectStatus().isNoContent();

        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByCorrelationId(correlationId).orElseThrow();
        assertThat(persisted.getUploadId()).isEqualTo(uploadId);
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
        assertThat(persisted.getFiles()).hasSize(1);
        assertThat(persisted.getFiles().get(0).filename()).isEqualTo("test.pdf");
        assertThat(persisted.getFiles().get(0).s3Key()).isEqualTo(S3_KEY_FROM_FIXTURE);
    }

    @Test
    void scanResult_mixedCompleteAndRejected_shouldSetAggregateStatusToRejected() {
        AccompanyingDocument seed = persistPendingDocument();
        String correlationId = seed.getCorrelationId();
        String uploadId = seed.getUploadId();

        String mixedPayload = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": 1,
              "metadata": {"correlationId": "%s"},
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
            """.formatted(correlationId, uploadId, DOCUMENTS_BUCKET);

        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mixedPayload)
            .exchange()
            .expectStatus().isNoContent();

        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
        assertThat(persisted.getFiles()).hasSize(2);

        var cleanFile = persisted.getFiles().stream()
            .filter(f -> FileStatus.COMPLETE.equals(f.fileStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("clean file not found"));
        assertThat(cleanFile.s3Key()).isEqualTo(uploadId + "/file-clean-001");

        var virusFile = persisted.getFiles().stream()
            .filter(f -> FileStatus.REJECTED.equals(f.fileStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("virus file not found"));
        assertThat(virusFile.s3Key()).isNull();
    }

    /**
     * Defensive: if cdp-uploader omits {@code numberOfRejectedFiles}, the resolver must treat the
     * absent value as REJECTED rather than silently accepting the file.
     */
    @Test
    void scanResult_nullNumberOfRejectedFiles_shouldSetStatusToRejected() {
        AccompanyingDocument seed = persistPendingDocument();
        String correlationId = seed.getCorrelationId();
        String uploadId = seed.getUploadId();

        String payloadWithNullRejectedCount = """
            {
              "uploadStatus": "ready",
              "numberOfRejectedFiles": null,
              "metadata": {"correlationId": "%s"},
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
            """.formatted(correlationId, FILE_ID_FROM_FIXTURE, S3_KEY_FROM_FIXTURE);

        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + uploadId + "/scan-results")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payloadWithNullRejectedCount)
            .exchange()
            .expectStatus().isNoContent();

        AccompanyingDocument persisted =
            accompanyingDocumentRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(persisted.getScanStatus()).isEqualTo(ScanStatus.REJECTED);
    }

    /**
     * Persists a PENDING {@link AccompanyingDocument} with a generated correlationId and uploadId
     * — the minimum state needed to drive the scan-callback handler against this seed.
     */
    private AccompanyingDocument persistPendingDocument() {
        String correlationId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        AccompanyingDocument doc = AccompanyingDocument.builder()
            .notificationReferenceNumber(NOTIFICATION_REF)
            .uploadId(uploadId)
            .correlationId(correlationId)
            .uploadUrl("http://stub/upload/" + uploadId)
            .documentType(DocumentType.ITAHC)
            .documentReference("UKGB2026001234")
            .scanStatus(ScanStatus.PENDING)
            .files(new ArrayList<>())
            .build();
        return accompanyingDocumentRepository.save(doc);
    }

    private String loadCallbackFixture(String classpathResource, String correlationId)
        throws IOException {
        return loadFixtureAsString(classpathResource).replace("__CORRELATION_ID__", correlationId);
    }
}
