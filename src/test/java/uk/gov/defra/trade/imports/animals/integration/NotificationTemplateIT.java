package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateController;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateListResponse;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateRepository;

class NotificationTemplateIT extends IntegrationBase {

    private static final String TEMPLATES_ENDPOINT = "/templates";
    private static final String HEADER_USER_ID = NotificationTemplateController.HEADER_USER_ID;
    private static final String OWNER = "2100010101";
    private static final String OTHER_OWNER = "2100010102";

    @Autowired
    private NotificationTemplateRepository repository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void findAll_shouldReturnNoTemplates_whenOwnerHasSavedNone() {
        // Given
        seed(OWNER, "Acorn Farm", Instant.parse("2026-10-01T10:00:00Z"));

        // When
        NotificationTemplateListResponse response = list(OTHER_OWNER);

        // Then
        assertThat(response.items()).isEmpty();
        assertThat(response.totalItems()).isZero();
    }

    @Test
    void findAll_shouldReturnOnlyTheOwnersTemplates_newestFirst() {
        // Given
        seed(OWNER, "Acorn Farm", Instant.parse("2026-10-01T10:00:00Z"));
        seed(OWNER, "Arena Aintree", Instant.parse("2026-10-09T10:00:00Z"));
        seed(OTHER_OWNER, "Not Mine", Instant.parse("2026-10-05T10:00:00Z"));

        // When
        NotificationTemplateListResponse response = list(OWNER);

        // Then
        List<String> names = response.items().stream().map(item -> item.name()).toList();
        assertThat(names).containsExactly("Arena Aintree", "Acorn Farm");
        assertThat(response.totalItems()).isEqualTo(2);
    }

    @Test
    void findAll_shouldReturn400_whenUserIdHeaderIsMissing() {
        // When / Then
        webClient("NoAuth")
            .get()
            .uri(TEMPLATES_ENDPOINT)
            .exchange()
            .expectStatus().isBadRequest();
    }

    private NotificationTemplateListResponse list(String ownerId) {
        return webClient("NoAuth")
            .get()
            .uri(TEMPLATES_ENDPOINT)
            .header(HEADER_USER_ID, ownerId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationTemplateListResponse.class)
            .returnResult()
            .getResponseBody();
    }

    /** Inserts a raw row: auditing would overwrite a builder-set {@code created} on save. */
    private void seed(String ownerId, String name, Instant created) {
        mongoTemplate.getCollection("notification_templates").insertOne(
            new Document("ownerId", ownerId)
                .append("organisationId", "5900001")
                .append("setId", "live-animals")
                .append("name", name)
                .append("fulfilments", List.of())
                .append("created", Date.from(created)));
    }
}
