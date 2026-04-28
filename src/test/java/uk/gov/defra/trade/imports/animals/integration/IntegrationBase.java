package uk.gov.defra.trade.imports.animals.integration;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.testcontainers.utility.DockerImageName.parse;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.JsonBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
abstract class IntegrationBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationBase.class);
    static final List<String> SERVICES_TO_MOCK = List.of();

    @LocalServerPort
    int port;
    

    private MockServerClient mockServerClient;

    static final OAuthMockServerContainer OAUTH_CONTAINER = new OAuthMockServerContainer();
    static final MockServerContainer MOCK_SERVER_CONTAINER = new MockServerContainer(
        parse("mockserver/mockserver").withTag(
            "mockserver-" + MockServerClient.class.getPackage().getImplementationVersion()));

    static MongoDBContainer MONGO_CONTAINER = new MongoDBContainer(
        DockerImageName.parse("mongo:7.0")).withExposedPorts(27017);

    static {
        Startables.deepStart(
            OAUTH_CONTAINER, MONGO_CONTAINER, MOCK_SERVER_CONTAINER
        ).join();
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {

        // Service API urls
        SERVICES_TO_MOCK.forEach(
            service -> registry.add("%s.url".formatted(service),
                () -> "%s/%s/".formatted(MOCK_SERVER_CONTAINER.getEndpoint(), service)));

        // Oauth Server container config
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

    /**
     * The main MockServerClient to be used for stubbing out the requests that we need to be
     * verifiable.
     *
     * @return the MockServerClient to be used for stubbing out external services.
     */
    MockServerClient usingStub() {
        if (mockServerClient == null) {
            mockServerClient = new MockServerClient(MOCK_SERVER_CONTAINER.getHost(),
                MOCK_SERVER_CONTAINER.getServerPort());
            LOGGER.info(
                "You should be able to find the dashboard here : http://{}:{}/mockserver/dashboard",
                MOCK_SERVER_CONTAINER.getHost(), MOCK_SERVER_CONTAINER.getServerPort());
        }
        return mockServerClient;
    }

    WebTestClient webClient(String clientType) {
        return WebTestClient.bindToServer()
            .baseUrl("http://localhost:%d".formatted(port))
            .defaultHeader("Authorization", "Bearer " + getToken(clientType))
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("INS-ConversationId", UUID.randomUUID().toString())
            .build();
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
    
    @SneakyThrows
    JsonBody getJsonFromFile(String filename) {
        return new JsonBody(
            Files.readString(Paths.get(getClass().getClassLoader().getResource(filename).toURI())));
    }

    @AfterEach
    void tearDown() {
        usingStub().reset();
    }

    <T> T getResponseAsObject(byte[] bytes, Class<T> clazz) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature());
            mapper.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false);
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return bytes.length > 0
                ? mapper.reader().forType(clazz).readValue(bytes)
                : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads a classpath fixture file as a UTF-8 String.
     */
    protected String loadFixtureAsString(String classpathResource) throws IOException {
        return new String(readResourceBytes(classpathResource), StandardCharsets.UTF_8);
    }

    /**
     * Loads a classpath fixture file as raw bytes.
     */
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
