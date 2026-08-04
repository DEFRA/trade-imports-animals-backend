package uk.gov.defra.trade.imports.animals.notificationfulfilments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;

@ExtendWith(MockitoExtension.class)
class NotificationFulfilmentsServiceTest {

    private static final String ID = "GBN-AG-26-ABC123";
    private static final String TRACE_ID = "trace-fulfilment-001";

    @Mock
    private NotificationFulfilmentsRepository notificationFulfilmentsRepository;

    @Mock
    private ReferenceNumberGenerator referenceNumberGenerator;

    @Mock
    private MongoTemplate mongoTemplate;

    private NotificationFulfilmentsService notificationFulfilmentsService;

    @BeforeEach
    void setUp() {
        notificationFulfilmentsService =
            new NotificationFulfilmentsService(
                notificationFulfilmentsRepository, referenceNumberGenerator,
                mongoTemplate, 20);
    }

    @Test
    void findAll_shouldComposeTrimmedReferenceWithStatusCriteriaAndSort() {
        NotificationFulfilmentsPageResponse.Item item = new NotificationFulfilmentsPageResponse.Item(
            ID,
            NotificationFulfilmentsStatus.DRAFT,
            LocalDateTime.of(2026, 7, 30, 12, 0),
            null,
            ID,
            null,
            null,
            null,
            null,
            null);
        when(mongoTemplate.count(any(Query.class), eq(NotificationFulfilments.class)))
            .thenReturn(1L);
        when(mongoTemplate.getCollectionName(
            uk.gov.defra.trade.imports.animals.notification.Notification.class))
            .thenReturn("notification");
        when(mongoTemplate.aggregate(
            any(Aggregation.class),
            eq(NotificationFulfilments.class),
            eq(NotificationFulfilmentsPageResponse.Item.class)))
            .thenReturn(new AggregationResults<>(List.of(item), new Document()));

        NotificationFulfilmentsPageResponse response =
            notificationFulfilmentsService.findAll(1, "createdAt,asc", "  " + ID + "  ");

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items()).containsExactly(item);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(queryCaptor.capture(), eq(NotificationFulfilments.class));
        assertThat(queryCaptor.getValue().getQueryObject().getString("_id")).isEqualTo(ID);

        ArgumentCaptor<Aggregation> aggregationCaptor =
            ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(
            aggregationCaptor.capture(),
            eq(NotificationFulfilments.class),
            eq(NotificationFulfilmentsPageResponse.Item.class));
        List<AggregationOperation> operations =
            aggregationCaptor.getValue().getPipeline().getOperations();
        Document matchStage =
            operations.getFirst().toDocument(Aggregation.DEFAULT_CONTEXT);
        Document sortStage =
            operations.get(4).toDocument(Aggregation.DEFAULT_CONTEXT);
        assertThat(matchStage.get("$match", Document.class).getString("_id"))
            .isEqualTo(ID);
        assertThat(sortStage.get("$sort", Document.class))
            .containsEntry("createdAt", 1)
            .containsEntry("_id", 1);
    }

    @Test
    void findAll_shouldReturnEmptyPageWhenReferenceDoesNotMatch() {
        when(mongoTemplate.count(any(Query.class), eq(NotificationFulfilments.class)))
            .thenReturn(0L);
        when(mongoTemplate.getCollectionName(
            uk.gov.defra.trade.imports.animals.notification.Notification.class))
            .thenReturn("notification");
        when(mongoTemplate.aggregate(
            any(Aggregation.class),
            eq(NotificationFulfilments.class),
            eq(NotificationFulfilmentsPageResponse.Item.class)))
            .thenReturn(new AggregationResults<>(List.of(), new Document()));

        NotificationFulfilmentsPageResponse response =
            notificationFulfilmentsService.findAll(1, null, "GBN-AG-26-ZZZZZZ");

        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.items()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFulfilmentsStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void copy_shouldCreateDraftFromCopyableStatus(NotificationFulfilmentsStatus sourceStatus) {
        String copyId = "GBN-AG-26-ABC124";
        String idempotencyKey = "copy-key";
        List<Document> sourceContent = List.of(new Document("value", sourceStatus.name()));
        NotificationFulfilments source = fulfilment(sourceStatus, sourceContent, List.of());
        when(notificationFulfilmentsRepository.findByCopyIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(source));
        when(referenceNumberGenerator.generate()).thenReturn(copyId);
        when(notificationFulfilmentsRepository.insert(any(NotificationFulfilments.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationFulfilments copy = notificationFulfilmentsService.copy(ID, idempotencyKey);

        assertThat(copy.getId()).isEqualTo(copyId).isNotEqualTo(ID);
        assertThat(copy.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DRAFT);
        assertThat(copy.getCreatedAt()).isNotNull();
        assertThat(copy.getSubmittedAt()).isNull();
        assertThat(copy.getSubmittedFulfilments()).isNull();
        assertThat(copy.getFulfilments())
            .isEqualTo(sourceContent)
            .isNotSameAs(sourceContent);
        assertThat(copy.getCopyIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    void copy_shouldReturnExistingCopyForSameIdempotencyKey() {
        String idempotencyKey = "copy-key";
        NotificationFulfilments existingCopy = fulfilment(NotificationFulfilmentsStatus.DRAFT, List.of(), null);
        when(notificationFulfilmentsRepository.findByCopyIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.of(existingCopy));

        NotificationFulfilments result = notificationFulfilmentsService.copy(ID, idempotencyKey);

        assertThat(result).isSameAs(existingCopy);
        verify(notificationFulfilmentsRepository, never()).findById(any());
        verify(notificationFulfilmentsRepository, never()).insert(any(NotificationFulfilments.class));
        verify(referenceNumberGenerator, never()).generate();
    }

    @Test
    void copy_shouldRejectDeletedSource() {
        String idempotencyKey = "copy-key";
        NotificationFulfilments deleted = fulfilment(NotificationFulfilmentsStatus.DELETED, List.of(), null);
        when(notificationFulfilmentsRepository.findByCopyIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> notificationFulfilmentsService.copy(ID, idempotencyKey))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot copy fulfilment with status: DELETED");

        verify(notificationFulfilmentsRepository, never()).insert(any(NotificationFulfilments.class));
    }

    @Test
    void copy_shouldRejectBlankIdempotencyKey() {
        assertThatThrownBy(() -> notificationFulfilmentsService.copy(ID, " "))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Idempotency-Key must not be blank");

        verify(notificationFulfilmentsRepository, never()).findById(any());
        verify(notificationFulfilmentsRepository, never()).insert(any(NotificationFulfilments.class));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFulfilmentsStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void softDelete_shouldSetDeletedForDeletableStatus(NotificationFulfilmentsStatus sourceStatus) {
        NotificationFulfilments fulfilment = fulfilment(sourceStatus, List.of(), null);
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(notificationFulfilmentsRepository.save(any(NotificationFulfilments.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationFulfilments deleted = notificationFulfilmentsService.softDelete(ID);

        assertThat(deleted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.DELETED);
        verify(notificationFulfilmentsRepository).save(fulfilment);
    }

    @Test
    void softDelete_shouldReturnAlreadyDeletedUnchanged() {
        NotificationFulfilments deleted = fulfilment(NotificationFulfilmentsStatus.DELETED, List.of(), null);
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(deleted));

        NotificationFulfilments result = notificationFulfilmentsService.softDelete(ID);

        assertThat(result).isSameAs(deleted);
        verify(notificationFulfilmentsRepository, never()).save(any());
    }

    @Test
    void amend_shouldCaptureSnapshotThatIsUnaffectedByReplace() {
        List<Document> submittedContent = List.of(new Document("value", "submitted"));
        NotificationFulfilments fulfilment = fulfilment(
            NotificationFulfilmentsStatus.SUBMITTED, submittedContent, null);
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(notificationFulfilmentsRepository.save(any(NotificationFulfilments.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationFulfilments amended = notificationFulfilmentsService.amend(ID, TRACE_ID, null);

        assertThat(amended.getStatus()).isEqualTo(NotificationFulfilmentsStatus.AMEND);
        assertThat(amended.getSubmittedFulfilments())
            .isEqualTo(submittedContent)
            .isNotSameAs(submittedContent);
        assertThat(amended.getSubmittedAt()).isNull();

        List<Document> amendedContent = List.of(new Document("value", "amended"));
        notificationFulfilmentsService.replace(
            ID,
            NotificationFulfilmentsDto.builder().id(ID).fulfilments(amendedContent).build());

        assertThat(fulfilment.getFulfilments()).isEqualTo(amendedContent);
        assertThat(fulfilment.getSubmittedFulfilments()).isEqualTo(submittedContent);
    }

    @Test
    void cancelAmend_shouldRestoreSnapshotAndSetSubmitted() {
        List<Document> submittedContent = List.of(new Document("value", "submitted"));
        NotificationFulfilments fulfilment = fulfilment(
            NotificationFulfilmentsStatus.AMEND,
            List.of(new Document("value", "amended")),
            submittedContent);
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(notificationFulfilmentsRepository.save(any(NotificationFulfilments.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime beforeCancel = LocalDateTime.now();

        NotificationFulfilments restored = notificationFulfilmentsService.cancelAmend(ID);

        assertThat(restored.getFulfilments()).isEqualTo(submittedContent);
        assertThat(restored.getStatus()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(restored.getSubmittedFulfilments()).isNull();
        assertThat(restored.getSubmittedAt()).isAfterOrEqualTo(beforeCancel);
        verify(notificationFulfilmentsRepository).save(fulfilment);
    }

    @Test
    void cancelAmend_shouldRejectNonAmendStatus() {
        NotificationFulfilments fulfilment = fulfilment(NotificationFulfilmentsStatus.SUBMITTED, List.of(), null);
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(fulfilment));

        assertThatThrownBy(() -> notificationFulfilmentsService.cancelAmend(ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUBMITTED");

        verify(notificationFulfilmentsRepository, never()).save(any());
    }

    @Test
    void cancelAmend_shouldRejectMissingSubmittedSnapshot() {
        NotificationFulfilments fulfilment = fulfilment(NotificationFulfilmentsStatus.AMEND, List.of(), null);
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(fulfilment));

        assertThatThrownBy(() -> notificationFulfilmentsService.cancelAmend(ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no submitted snapshot");

        verify(notificationFulfilmentsRepository, never()).save(any());
    }

    @Test
    void submitFromAmend_shouldClearSubmittedSnapshot() {
        NotificationFulfilments fulfilment = fulfilment(
            NotificationFulfilmentsStatus.AMEND,
            List.of(new Document("value", "amended")),
            List.of(new Document("value", "submitted")));
        when(notificationFulfilmentsRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(notificationFulfilmentsRepository.save(any(NotificationFulfilments.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationFulfilments submitted = notificationFulfilmentsService.submit(ID, TRACE_ID, null);

        assertThat(submitted.getStatus()).isEqualTo(NotificationFulfilmentsStatus.SUBMITTED);
        assertThat(submitted.getSubmittedFulfilments()).isNull();
        assertThat(submitted.getSubmittedAt()).isNotNull();
    }

    private NotificationFulfilments fulfilment(
        NotificationFulfilmentsStatus status,
        List<Document> content,
        List<Document> submittedContent) {
        return NotificationFulfilments.builder()
            .id(ID)
            .fulfilments(content)
            .submittedFulfilments(submittedContent)
            .status(status)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
