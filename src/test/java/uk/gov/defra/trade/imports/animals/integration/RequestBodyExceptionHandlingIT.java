package uk.gov.defra.trade.imports.animals.integration;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

class RequestBodyExceptionHandlingIT extends IntegrationBase {

    private static final String REFERENCE = "GBN-AG-26-ABC123";
    private static final List<String> UNREADABLE_BODIES = List.of("{not-json", "null");

    @Test
    void notifications_shouldReturnProblem400ForUnreadableBodies() {
        assertUnreadableBodies(HttpMethod.POST, "/notifications");
    }

    @Test
    void notificationFulfilments_shouldReturnProblem400ForUnreadableBodies() {
        assertUnreadableBodies(HttpMethod.PUT, "/notification-fulfilments/" + REFERENCE);
    }

    @Test
    void documentUploads_shouldReturnProblem400ForUnreadableBodies() {
        assertUnreadableBodies(
            HttpMethod.POST,
            "/notifications/" + REFERENCE + "/document-uploads");
    }

    @Test
    void frameworkExceptions_shouldRetainTheirMeaningfulStatuses() {
        webClient("NoAuth")
            .post()
            .uri("/notifications")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(415);

        webClient("NoAuth")
            .patch()
            .uri("/notifications")
            .exchange()
            .expectStatus().isEqualTo(405);

        webClient("NoAuth")
            .post()
            .uri("/document-uploads/" + REFERENCE + "/file")
            .body(BodyInserters.fromMultipartData(new LinkedMultiValueMap<>()))
            .exchange()
            .expectStatus().isBadRequest();

        webClient("NoAuth")
            .get()
            .uri("/notifications?page=not-a-number")
            .exchange()
            .expectStatus().isBadRequest();
    }

    private void assertUnreadableBodies(HttpMethod method, String uri) {
        for (String body : UNREADABLE_BODIES) {
            assertProblemBadRequest(webClient("NoAuth")
                .method(method)
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange());
        }

        assertProblemBadRequest(webClient("NoAuth")
            .method(method)
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .exchange());
    }

    private static void assertProblemBadRequest(WebTestClient.ResponseSpec response) {
        response
            .expectStatus().isBadRequest()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.title").isEqualTo("Bad Request")
            .jsonPath("$.detail").isEqualTo("Request body is missing or malformed")
            .jsonPath("$.stackTrace").doesNotExist();
    }
}
