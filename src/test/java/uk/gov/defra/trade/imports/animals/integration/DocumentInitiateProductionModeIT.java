package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentUploadResponse;

/**
 * Pins the cdp-uploader contract under {@code NODE_ENV=production} — the mode in which deployed
 * environments run — where the {@code relativeOnly} constraint on the {@code redirect} Joi
 * schema is enforced and the {@code callback} URL must be on the cdp-int.defra.cloud domain.
 *
 * <p>The companion {@link DocumentIT} runs cdp-uploader in development mode (which is what
 * permits the mock virus scanner SQS listener to start) so the contract drift between local CI
 * and the deployed environments would otherwise go uncaught. This IT exists to catch it.
 */
@Slf4j
class DocumentInitiateProductionModeIT extends IntegrationBase {

    private static final String NOTIFICATION_REF = "DRAFT.IMP.2026.PROD-IT001";

    private static final Network CONTAINER_NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS_CONTAINER =
        new GenericContainer<>(DockerImageName.parse("redis:7.2.3-alpine3.18"))
            .withExposedPorts(6379)
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("redis-prod-it")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));

    // cdp-uploader running in production mode — flips on the prod-only Joi schema rules:
    //   - redirect must be a relative URI
    //   - callback must be on the cdp-int.defra.cloud domain
    @SuppressWarnings("resource")
    private static final GenericContainer<?> CDP_UPLOADER_PROD_CONTAINER =
        new GenericContainer<>(DockerImageName.parse("defradigital/cdp-uploader:latest"))
            .withExposedPorts(3000)
            .withNetwork(CONTAINER_NETWORK)
            .withNetworkAliases("cdp-uploader-prod")
            .withEnv("PORT", "3000")
            .withEnv("NODE_ENV", "production")
            .withEnv("REDIS_HOST", "redis-prod-it")
            .withEnv("USE_SINGLE_INSTANCE_CACHE", "true")
            .withEnv("CONSUMER_BUCKETS", "trade-imports-animals-documents")
            .waitingFor(Wait.forHttp("/health").forPort(3000)
                .withStartupTimeout(Duration.ofSeconds(60)));

    static {
        Startables.deepStart(REDIS_CONTAINER).join();
        Startables.deepStart(CDP_UPLOADER_PROD_CONTAINER).join();
        log.info("Production-mode cdp-uploader started on port {}",
            CDP_UPLOADER_PROD_CONTAINER.getMappedPort(3000));
    }

    @DynamicPropertySource
    static void registerProductionModeProperties(DynamicPropertyRegistry registry) {
        registry.add("cdp.uploader.base-url",
            () -> "http://" + CDP_UPLOADER_PROD_CONTAINER.getHost()
                + ":" + CDP_UPLOADER_PROD_CONTAINER.getMappedPort(3000));

        // Callback URL must end in cdp-int.defra.cloud to satisfy prod-mode Joi schema.
        // Reachability doesn't matter here — /initiate only validates the URL string.
        registry.add("cdp.backend.base-url", () -> "https://backend.dev.cdp-int.defra.cloud");
    }

    @Test
    void initiate_shouldReturn201_withRelativeRedirectInProductionMode() {
        EntityExchangeResult<DocumentUploadResponse> result = webClient("NoAuth")
            .post()
            .uri("/notifications/" + NOTIFICATION_REF + "/document-uploads")
            .bodyValue("""
                {"documentType":"ITAHC","documentReference":"UKGB2026001234","dateOfIssue":"2026-01-15"}
                """)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(DocumentUploadResponse.class)
            .returnResult();

        DocumentUploadResponse body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.uploadId()).isNotBlank();
        assertThat(body.uploadUrl()).contains("/upload-and-scan/" + body.uploadId());
    }

    /**
     * Pins the cdp-uploader contract that the original EUDPA-35 bug tripped over: in
     * production mode the {@code redirect} field is validated as relative-only. Bypasses our
     * backend (which now hard-codes a relative path) and posts directly to cdp-uploader's
     * {@code /initiate} so a future cdp-uploader image bump that relaxes
     * {@code relativeOnly} would fail this test rather than silently weaken the guard.
     */
    @Test
    void cdpUploaderInitiate_shouldReject400_whenRedirectIsAbsoluteUrl() {
        String cdpUploaderUrl = "http://" + CDP_UPLOADER_PROD_CONTAINER.getHost()
            + ":" + CDP_UPLOADER_PROD_CONTAINER.getMappedPort(3000);

        EntityExchangeResult<byte[]> result = WebTestClient.bindToServer()
            .baseUrl(cdpUploaderUrl)
            .build()
            .post()
            .uri("/initiate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "redirect": "https://frontend.dev.cdp-int.defra.cloud/accompanying-documents",
                  "callback": "https://backend.dev.cdp-int.defra.cloud/document-uploads/pending/scan-results",
                  "s3Bucket": "trade-imports-animals-documents",
                  "s3Path": "DRAFT.IMP.2026.PROD-IT001",
                  "metadata": {}
                }
                """)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().returnResult();

        String body = result.getResponseBody() == null ? "" : new String(result.getResponseBody());
        assertThat(body).contains("redirect").contains("relative");
    }
}
