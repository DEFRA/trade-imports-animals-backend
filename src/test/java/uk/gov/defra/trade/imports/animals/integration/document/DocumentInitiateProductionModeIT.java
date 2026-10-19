package uk.gov.defra.trade.imports.animals.integration.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentUploadResponse;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;

@Slf4j
class DocumentInitiateProductionModeIT extends IntegrationBase {

    private static final String NOTIFICATION_REF = "GBN-AG-26-PD1001";
    private static final String REDIS_ALIAS = "redis-prod-it";
    private static final String CDP_UPLOADER_ALIAS = "cdp-uploader-prod";

    private static final Network CONTAINER_NETWORK = Network.newNetwork();

    private static final GenericContainer<?> REDIS_CONTAINER =
        CdpUploaderTestSupport.redisContainer(CONTAINER_NETWORK, REDIS_ALIAS);

    private static final GenericContainer<?> CDP_UPLOADER_PROD_CONTAINER =
        CdpUploaderTestSupport.cdpUploaderContainer(
                CONTAINER_NETWORK, CDP_UPLOADER_ALIAS, REDIS_ALIAS, CdpUploaderTestSupport.DOCUMENTS_BUCKET)
            .withEnv("NODE_ENV", "production");

    static {
        Startables.deepStart(REDIS_CONTAINER).join();
        Startables.deepStart(CDP_UPLOADER_PROD_CONTAINER).join();
        log.info("Production-mode cdp-uploader started on port {}",
            CDP_UPLOADER_PROD_CONTAINER.getMappedPort(CdpUploaderTestSupport.CDP_UPLOADER_PORT));
    }

    @DynamicPropertySource
    static void registerProductionModeProperties(DynamicPropertyRegistry registry) {
        registry.add("cdp.uploader.base-url",
            () -> "http://" + CDP_UPLOADER_PROD_CONTAINER.getHost()
                + ":" + CDP_UPLOADER_PROD_CONTAINER.getMappedPort(CdpUploaderTestSupport.CDP_UPLOADER_PORT));
        registry.add("app.base-url", () -> "https://backend.dev.cdp-int.defra.cloud");
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
        assertThat(body.uploadUrl()).contains("/document-uploads/" + body.uploadId() + "/file");
        assertThat(body.uploadUrl()).startsWith("https://");
    }

    @Test
    void cdpUploaderInitiate_shouldReject400_whenRedirectIsAbsoluteUrl() {
        String cdpUploaderUrl = "http://" + CDP_UPLOADER_PROD_CONTAINER.getHost()
            + ":" + CDP_UPLOADER_PROD_CONTAINER.getMappedPort(CdpUploaderTestSupport.CDP_UPLOADER_PORT);

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
                  "s3Bucket": "%s",
                  "s3Path": "%s",
                  "metadata": {}
                }
                """.formatted(CdpUploaderTestSupport.DOCUMENTS_BUCKET, NOTIFICATION_REF))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().returnResult();

        String body = result.getResponseBody() == null
            ? ""
            : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\\\"redirect\\\" must be a valid relative uri");
    }
}
