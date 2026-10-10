package uk.gov.defra.trade.imports.animals.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    private static final String OWNER_ID = "2100010101";

    @Mock
    private NotificationTemplateRepository repository;

    private NotificationTemplateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationTemplateService(repository);
    }

    @Test
    void listFor_shouldReturnNoTemplates_whenOwnerHasSavedNone() {
        // Given
        when(repository.findAllByOwnerIdOrderByCreatedDesc(OWNER_ID)).thenReturn(List.of());

        // When
        NotificationTemplateListResponse response = service.listFor(OWNER_ID);

        // Then
        assertThat(response.items()).isEmpty();
        assertThat(response.totalItems()).isZero();
    }

    @Test
    void listFor_shouldSummariseEachTemplateInTheOrderStored() {
        // Given
        Instant newer = Instant.parse("2026-10-09T10:00:00Z");
        Instant older = Instant.parse("2026-10-01T10:00:00Z");
        NotificationTemplate first = template("t-1", "Arena Aintree", newer);
        NotificationTemplate second = template("t-2", "Acorn Farm", older);
        when(repository.findAllByOwnerIdOrderByCreatedDesc(OWNER_ID))
            .thenReturn(List.of(first, second));

        // When
        NotificationTemplateListResponse response = service.listFor(OWNER_ID);

        // Then
        assertThat(response.items()).containsExactly(
            new NotificationTemplateSummary("t-1", "Arena Aintree", "live-animals", newer),
            new NotificationTemplateSummary("t-2", "Acorn Farm", "live-animals", older));
        assertThat(response.totalItems()).isEqualTo(2);
    }

    private static NotificationTemplate template(String id, String name, Instant created) {
        return NotificationTemplate.builder()
            .id(id)
            .ownerId(OWNER_ID)
            .setId("live-animals")
            .name(name)
            .fulfilments(List.of(new Document("id", "origin")))
            .created(created)
            .build();
    }
}
