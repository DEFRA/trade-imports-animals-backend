package uk.gov.defra.trade.imports.animals.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    private static final String OWNER_ID = "2100010101";
    private static final String TEMPLATE_ID = "6650f1c2a1b2c3d4e5f60718";

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
        when(repository.findAllByOwnerIdAndStatusNotOrderBySavedDescCreatedDesc(
            OWNER_ID, TemplateStatus.IN_PROGRESS)).thenReturn(List.of());

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
        when(repository.findAllByOwnerIdAndStatusNotOrderBySavedDescCreatedDesc(
            OWNER_ID, TemplateStatus.IN_PROGRESS)).thenReturn(List.of(first, second));

        // When
        NotificationTemplateListResponse response = service.listFor(OWNER_ID);

        // Then
        assertThat(response.items()).containsExactly(
            new NotificationTemplateSummary("t-1", "Arena Aintree", "live-animals", newer),
            new NotificationTemplateSummary("t-2", "Acorn Farm", "live-animals", older));
        assertThat(response.totalItems()).isEqualTo(2);
    }

    @Test
    void start_shouldSaveAnInProgressTemplateWithOwnerOrganisationTypeAndName() {
        // Given
        when(repository.save(any(NotificationTemplate.class))).thenAnswer(call -> {
            NotificationTemplate saved = call.getArgument(0);
            saved.setId(TEMPLATE_ID);
            return saved;
        });

        // When
        NotificationTemplateResponse response = service.start(
            OWNER_ID, new StartTemplateRequest("live-animals", "Weekly cattle", "5900001"));

        // Then
        ArgumentCaptor<NotificationTemplate> captor =
            ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(repository).save(captor.capture());
        NotificationTemplate saved = captor.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(saved.getOrganisationId()).isEqualTo("5900001");
        assertThat(saved.getSetId()).isEqualTo("live-animals");
        assertThat(saved.getName()).isEqualTo("Weekly cattle");
        assertThat(saved.getStatus()).isEqualTo(TemplateStatus.IN_PROGRESS);
        assertThat(response.id()).isEqualTo(TEMPLATE_ID);
        assertThat(response.status()).isEqualTo(TemplateStatus.IN_PROGRESS);
    }

    @Test
    void get_shouldReturnTheTemplate_whenTheOwnerHasIt() {
        // Given
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(template(TEMPLATE_ID, "Arena Aintree", null)));

        // When
        NotificationTemplateResponse response = service.get(OWNER_ID, TEMPLATE_ID);

        // Then
        assertThat(response.name()).isEqualTo("Arena Aintree");
        assertThat(response.status()).isEqualTo(TemplateStatus.SAVED);
    }

    @Test
    void get_shouldReturnWhenTheTemplateWasFirstSaved() {
        // Given
        Instant firstSaved = Instant.parse("2026-10-09T10:15:30Z");
        NotificationTemplate existing = template(TEMPLATE_ID, "Arena Aintree", null);
        existing.setSaved(firstSaved);
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(existing));

        // When
        NotificationTemplateResponse response = service.get(OWNER_ID, TEMPLATE_ID);

        // Then
        assertThat(response.saved()).isEqualTo(firstSaved);
    }

    @Test
    void get_shouldThrowNotFound_whenTheFinderIsEmpty() {
        // Given
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.get(OWNER_ID, TEMPLATE_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Template not found");
    }

    @Test
    void updateDetails_shouldWriteTheTypeAndName() {
        // Given
        NotificationTemplate existing = template(TEMPLATE_ID, "Old", null);
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        // When
        NotificationTemplateResponse response = service.updateDetails(
            OWNER_ID, TEMPLATE_ID, new TemplateDetailsRequest("germinal-products", "New"));

        // Then
        assertThat(existing.getSetId()).isEqualTo("germinal-products");
        assertThat(existing.getName()).isEqualTo("New");
        assertThat(response.name()).isEqualTo("New");
    }

    @Test
    void replaceFulfilments_shouldWriteTheFulfilments() {
        // Given
        NotificationTemplate existing = template(TEMPLATE_ID, "Old", null);
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        List<Document> fulfilments = List.of(new Document("id", "commodities"));

        // When
        NotificationTemplateResponse response = service.replaceFulfilments(
            OWNER_ID, TEMPLATE_ID, new TemplateFulfilmentsRequest(fulfilments));

        // Then
        assertThat(existing.getFulfilments()).containsExactlyElementsOf(fulfilments);
        assertThat(response.fulfilments()).containsExactlyElementsOf(fulfilments);
    }

    @Test
    void save_shouldMarkTheTemplateSaved() {
        // Given
        NotificationTemplate existing = template(TEMPLATE_ID, "Weekly", null);
        existing.setStatus(TemplateStatus.IN_PROGRESS);
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        // When
        NotificationTemplateResponse response = service.save(OWNER_ID, TEMPLATE_ID);

        // Then
        assertThat(existing.getStatus()).isEqualTo(TemplateStatus.SAVED);
        assertThat(response.status()).isEqualTo(TemplateStatus.SAVED);
    }

    @Test
    void save_shouldRecordWhenTheTemplateWasFirstSaved() {
        // Given
        NotificationTemplate existing = template(TEMPLATE_ID, "Weekly", null);
        existing.setStatus(TemplateStatus.IN_PROGRESS);
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        Instant before = Instant.now();

        // When
        service.save(OWNER_ID, TEMPLATE_ID);

        // Then
        Instant after = Instant.now();
        assertThat(existing.getSaved()).isNotNull().isBetween(before, after);
    }

    @Test
    void save_shouldKeepTheFirstSavedTime_whenSavedAgain() {
        // Given
        Instant firstSaved = Instant.parse("2026-10-01T10:00:00Z");
        NotificationTemplate existing = template(TEMPLATE_ID, "Weekly", null);
        existing.setSaved(firstSaved);
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID))
            .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        // When
        service.save(OWNER_ID, TEMPLATE_ID);

        // Then
        assertThat(existing.getSaved()).isEqualTo(firstSaved);
    }

    @Test
    void save_shouldThrowNotFound_whenTheFinderIsEmpty() {
        // Given
        when(repository.findByIdAndOwnerId(TEMPLATE_ID, OWNER_ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.save(OWNER_ID, TEMPLATE_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Template not found");
        verify(repository, never()).save(any());
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
