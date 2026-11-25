package uk.gov.defra.trade.imports.animals.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.defra.trade.imports.animals.filter.RequestTracingFilter;
import uk.gov.defra.trade.imports.animals.filter.TraderOrganisationHeaderFilter;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.NotificationTestSupport;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;

/**
 * Base class for Spring Boot integration tests, activating the {@code integration-test} profile
 * on a random port and starting two shared Testcontainers in a static block: a
 * {@link OAuthMockServerContainer mock OAuth2 server} (token + JWKS issuer) and a MongoDB
 * container wired in via {@code spring.data.mongodb.uri}.
 *
 * <p>{@link #webClient(String)} returns a {@link WebTestClient} pre-authenticated with a token
 * minted by the mock OAuth server using {@code clientType} as the {@code client_id}; the token
 * is decided by the {@code /integration/oauth2-mock-server.json} fixture.
 * {@link #webClient(String)} also sends
 * {@link TraderOrganisationHeaderFilter#ORGANISATION_ID_HEADER} defaulting to
 * {@link #DEFAULT_TRADER_ORG_ID}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class IntegrationBase {

    protected static final String DEFAULT_TRADER_ORG_ID = "5900002";
    protected static final String NOTIFICATION_ENDPOINT = "/notifications";

    private static final Duration FIRST_REQUEST_WARM_UP_ALLOWANCE = Duration.ofSeconds(30);

    @LocalServerPort
    protected int port;

    @Autowired
    protected MongoTemplate mongoTemplate;

    protected static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    static final OAuthMockServerContainer OAUTH_CONTAINER = new OAuthMockServerContainer();

    static MongoDBContainer MONGO_CONTAINER =
        new MongoDBContainer(MONGO_IMAGE).withExposedPorts(27017).withReplicaSet();

    static {
        Startables.deepStart(OAUTH_CONTAINER, MONGO_CONTAINER).join();
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("auth-api.url",
            () -> "http://%s:%d/oauth2/token".formatted(OAUTH_CONTAINER.getHost(),
                OAUTH_CONTAINER.getMappedPort(8080)));
        registry.add("auth-api.resource", () -> "http://%s:%d".formatted(OAUTH_CONTAINER.getHost(),
            OAUTH_CONTAINER.getMappedPort(8080)));

        registry.add("spring.security.oauth2.client.provider.trade-platform.token-uri",
            () -> "http://%s:%d/trade-platform/token".formatted(OAUTH_CONTAINER.getHost(),
                OAUTH_CONTAINER.getMappedPort(8080)));
        registry.add(
            "spring.security.jwt.iss",
            () ->
                "http://%s:%d/default"
                    .formatted(OAUTH_CONTAINER.getHost(), OAUTH_CONTAINER.getMappedPort(8080)));
        registry.add(
            "spring.security.jwt.jwks",
            () ->
                "http://%s:%d/default/jwks"
                    .formatted(OAUTH_CONTAINER.getHost(), OAUTH_CONTAINER.getMappedPort(8080)));
        registry.add("spring.security.jwt.aud", () -> "integration-test");

        registry.add("spring.data.mongodb.uri", MONGO_CONTAINER::getReplicaSetUrl);
        registry.add("spring.data.mongodb.ssl.enabled", () -> "false");
    }

    protected WebTestClient webClient(String clientType) {
        return webClient(clientType, DEFAULT_TRADER_ORG_ID);
    }

    protected WebTestClient webClient(String clientType, String organisationId) {
        return WebTestClient.bindToServer()
            .baseUrl("http://localhost:%d".formatted(port))
            .defaultHeader("Authorization", "Bearer " + getToken(clientType))
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("INS-ConversationId", UUID.randomUUID().toString())
            .defaultHeader(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, organisationId)
            .responseTimeout(FIRST_REQUEST_WARM_UP_ALLOWANCE)
            .build();
    }

    protected NotificationAggregate createNewNotification(String traceId) {
        return webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .header(RequestTracingFilter.TRACE_ID_HEADER, traceId)
            .bodyValue(SaveNotificationDto.of(minimalNotificationDto()))
            .exchange().expectStatus().isOk()
            .expectBody(NotificationAggregate.class).returnResult()
            .getResponseBody();
    }

    protected static NotificationDto minimalNotificationDto() {
        return NotificationDto.builder()
            .origin(Origin.builder().countryCode("GB").requiresRegionCode("true").internalReference("REF123").build())
            .commodities(List.of(Commodity.builder().name("Live cattle").build()))
            .build();
    }

    protected void seedDraftNotificationOwnedBy(
            NotificationRepository repository, String referenceNumber) {
        NotificationTestSupport.deleteByReferenceNumber(mongoTemplate, referenceNumber);
        NotificationAggregate aggregate = NotificationAggregate.builder()
                .referenceNumber(referenceNumber)
                .organisationId(DEFAULT_TRADER_ORG_ID)
                .status(NotificationStatus.DRAFT)
                .created(Instant.now())
                .notification(new Notification())
                .build();
        repository.save(aggregate);
    }

    String getToken(String clientType) {
        final RestClient restClient =
            RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
                .baseUrl(
                    "http://%s:%d"
                        .formatted(OAUTH_CONTAINER.getHost(), OAUTH_CONTAINER.getMappedPort(8080)))
                .build();

        final ResponseEntity<JsonNode> response =
            restClient
                .post()
                .uri("/default/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=authorization_code&code=userid&client_id=" + clientType)
                .retrieve()
                .toEntity(JsonNode.class);

        return Optional.ofNullable(response.getBody())
            .map(body -> body.get("access_token"))
            .map(JsonNode::asText)
            .orElseThrow();
    }

    protected String loadFixtureAsString(String classpathResource) throws IOException {
        return new String(readResourceBytes(classpathResource), StandardCharsets.UTF_8);
    }

    protected byte[] loadFixtureAsBytes(String classpathResource) throws IOException {
        return readResourceBytes(classpathResource);
    }

    private byte[] readResourceBytes(String classpathResource) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Fixture not found: " + classpathResource);
            }
            return stream.readAllBytes();
        }
    }
}
