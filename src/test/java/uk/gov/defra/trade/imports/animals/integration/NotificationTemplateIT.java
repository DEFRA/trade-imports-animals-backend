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
import org.springframework.http.MediaType;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplate;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateController;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateListResponse;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateRepository;
import uk.gov.defra.trade.imports.animals.template.NotificationTemplateResponse;
import uk.gov.defra.trade.imports.animals.template.StartTemplateRequest;
import uk.gov.defra.trade.imports.animals.template.TemplateDetailsRequest;
import uk.gov.defra.trade.imports.animals.template.TemplateStatus;

class NotificationTemplateIT extends IntegrationBase {

    private static final String TEMPLATES_ENDPOINT = "/templates";
    private static final String HEADER_USER_ID = NotificationTemplateController.HEADER_USER_ID;
    private static final String OWNER = "2100010101";
    private static final String OTHER_OWNER = "2100010102";
    private static final String ORIGIN_ONLY = "{\"fulfilments\":[{\"id\":\"origin\"}]}";

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

    @Test
    void start_thenGet_returnsTheInProgressTemplateWithItsTypeAndName() {
        // When
        NotificationTemplateResponse started = start(OWNER, "germinal-products", "Weekly semen");
        NotificationTemplateResponse fetched = get(OWNER, started.id());

        // Then
        assertThat(fetched.status()).isEqualTo(TemplateStatus.IN_PROGRESS);
        assertThat(fetched.setId()).isEqualTo("germinal-products");
        assertThat(fetched.name()).isEqualTo("Weekly semen");
    }

    @Test
    void get_shouldReturn404_forAnotherOwnersTemplate() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Mine");

        // When / Then
        webClient("NoAuth")
            .get()
            .uri(TEMPLATES_ENDPOINT + "/" + started.id())
            .header(HEADER_USER_ID, OTHER_OWNER)
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void updateDetails_shouldChangeTypeAndName() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Before");

        // When
        webClient("NoAuth")
            .put()
            .uri(TEMPLATES_ENDPOINT + "/" + started.id() + "/details")
            .header(HEADER_USER_ID, OWNER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new TemplateDetailsRequest("germinal-products", "After"))
            .exchange()
            .expectStatus().isOk();

        // Then
        NotificationTemplateResponse fetched = get(OWNER, started.id());
        assertThat(fetched.setId()).isEqualTo("germinal-products");
        assertThat(fetched.name()).isEqualTo("After");
    }

    @Test
    void replaceFulfilments_shouldStoreThePayloadByteFaithfully() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Answers");
        String body = "{\"fulfilments\":[{\"id\":\"origin\",\"answers\":{\"a\":\"b\"}}]}";

        // When
        replaceFulfilments(OWNER, started.id(), body);

        // Then
        NotificationTemplate stored = repository.findById(started.id()).orElseThrow();
        assertThat(stored.getFulfilments()).hasSize(1);
        assertThat(stored.getFulfilments().get(0).getString("id")).isEqualTo("origin");
        assertThat(stored.getFulfilments().get(0).get("answers", Document.class).getString("a"))
            .isEqualTo("b");
    }

    @Test
    void findAll_shouldNotListAnInProgressTemplate() {
        // Given
        start(OWNER, "live-animals", "Still being made");

        // When
        NotificationTemplateListResponse response = list(OWNER);

        // Then
        assertThat(response.items()).isEmpty();
    }

    @Test
    void findAll_shouldStillListARowWithNoStatus() {
        // Given
        seed(OWNER, "Legacy", Instant.parse("2026-10-01T10:00:00Z"));

        // When
        NotificationTemplateListResponse response = list(OWNER);

        // Then
        assertThat(response.items()).hasSize(1);
    }

    @Test
    void save_thenList_listsTheTemplateWithItsAnswers() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Weekly cattle");
        replaceFulfilments(OWNER, started.id(), ORIGIN_ONLY);

        // When
        NotificationTemplateResponse saved = save(OWNER, started.id());

        // Then
        assertThat(saved.status()).isEqualTo(TemplateStatus.SAVED);
        assertThat(list(OWNER).items()).extracting(item -> item.name())
            .containsExactly("Weekly cattle");
        assertThat(list(OWNER).items().get(0).fulfilments())
            .extracting(document -> document.getString("id")).containsExactly("origin");
        NotificationTemplate stored = repository.findById(started.id()).orElseThrow();
        assertThat(stored.getFulfilments()).hasSize(1);
        assertThat(stored.getFulfilments().get(0).getString("id")).isEqualTo("origin");
    }

    @Test
    void save_thenGet_returnsWhenTheTemplateWasFirstSaved() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Weekly cattle");
        assertThat(get(OWNER, started.id()).saved()).isNull();

        // When
        save(OWNER, started.id());
        NotificationTemplateResponse fetched = get(OWNER, started.id());

        // Then
        assertThat(fetched.saved()).isNotNull();
        assertThat(fetched.saved()).isAfterOrEqualTo(fetched.created());
    }

    @Test
    void replaceFulfilments_onASavedTemplate_movesTheSavedTimeOn() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Weekly cattle");
        save(OWNER, started.id());
        Instant firstSaved = get(OWNER, started.id()).saved();

        // When
        replaceFulfilments(OWNER, started.id(), ORIGIN_ONLY);

        // Then
        assertThat(get(OWNER, started.id()).saved()).isAfter(firstSaved);
    }

    @Test
    void findAll_shouldListTheLaterSavedTemplateFirst() {
        // Given
        NotificationTemplateResponse startedFirst = start(OWNER, "live-animals", "Started first");
        NotificationTemplateResponse startedSecond =
            start(OWNER, "live-animals", "Started second");
        save(OWNER, startedSecond.id());
        save(OWNER, startedFirst.id());

        // When
        NotificationTemplateListResponse response = list(OWNER);

        // Then
        assertThat(response.items()).extracting(item -> item.name())
            .containsExactly("Started first", "Started second");
    }

    @Test
    void findAll_shouldListAChangedSavedTemplateFirst() {
        // Given
        NotificationTemplateResponse older = start(OWNER, "live-animals", "Saved first");
        NotificationTemplateResponse newer = start(OWNER, "live-animals", "Saved second");
        save(OWNER, older.id());
        save(OWNER, newer.id());

        // When
        replaceFulfilments(OWNER, older.id(), ORIGIN_ONLY);

        // Then
        assertThat(list(OWNER).items()).extracting(item -> item.name())
            .containsExactly("Saved first", "Saved second");
    }

    @Test
    void save_shouldReturn404_forAnotherOwnersTemplate() {
        // Given
        NotificationTemplateResponse started = start(OWNER, "live-animals", "Mine");

        // When / Then
        webClient("NoAuth")
            .post()
            .uri(TEMPLATES_ENDPOINT + "/" + started.id() + "/save")
            .header(HEADER_USER_ID, OTHER_OWNER)
            .exchange()
            .expectStatus().isNotFound();
        assertThat(list(OWNER).items()).isEmpty();
    }

    private void replaceFulfilments(String ownerId, String id, String body) {
        webClient("NoAuth")
            .put()
            .uri(TEMPLATES_ENDPOINT + "/" + id + "/fulfilments")
            .header(HEADER_USER_ID, ownerId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk();
    }

    private NotificationTemplateResponse save(String ownerId, String id) {
        return webClient("NoAuth")
            .post()
            .uri(TEMPLATES_ENDPOINT + "/" + id + "/save")
            .header(HEADER_USER_ID, ownerId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationTemplateResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private NotificationTemplateResponse start(String ownerId, String setId, String name) {
        return webClient("NoAuth")
            .post()
            .uri(TEMPLATES_ENDPOINT)
            .header(HEADER_USER_ID, ownerId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new StartTemplateRequest(setId, name, "5900001"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(NotificationTemplateResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private NotificationTemplateResponse get(String ownerId, String id) {
        return webClient("NoAuth")
            .get()
            .uri(TEMPLATES_ENDPOINT + "/" + id)
            .header(HEADER_USER_ID, ownerId)
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationTemplateResponse.class)
            .returnResult()
            .getResponseBody();
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
