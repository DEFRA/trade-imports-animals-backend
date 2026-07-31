package uk.gov.defra.trade.imports.animals.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import uk.gov.defra.trade.imports.animals.notification.NotificationResponse;
import uk.gov.defra.trade.imports.animals.notification.NotificationService;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.outbox.Actor;

@ExtendWith(MockitoExtension.class)
class FulfilmentServiceTest {

    private static final String ID = "GBN-AG-26-ABC123";
    private static final String TRACE_ID = "trace-fulfilment-001";

    @Mock
    private FulfilmentRepository fulfilmentRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ReferenceNumberGenerator referenceNumberGenerator;

    @Mock
    private MongoTemplate mongoTemplate;

    private FulfilmentService fulfilmentService;

    @BeforeEach
    void setUp() {
        fulfilmentService =
            new FulfilmentService(
                fulfilmentRepository, notificationService, referenceNumberGenerator,
                mongoTemplate, 20);
    }

    @Test
    void findAll_shouldComposeTrimmedReferenceWithStatusCriteriaAndSort() {
        FulfilmentPageResponse.Item item = new FulfilmentPageResponse.Item(
            ID,
            FulfilmentStatus.DRAFT,
            LocalDateTime.of(2026, 7, 30, 12, 0),
            null,
            ID,
            null,
            null,
            null,
            null,
            null);
        when(mongoTemplate.count(any(Query.class), eq(Fulfilment.class)))
            .thenReturn(1L);
        when(mongoTemplate.getCollectionName(
            uk.gov.defra.trade.imports.animals.notification.Notification.class))
            .thenReturn("notification");
        when(mongoTemplate.aggregate(
            any(Aggregation.class),
            eq(Fulfilment.class),
            eq(FulfilmentPageResponse.Item.class)))
            .thenReturn(new AggregationResults<>(List.of(item), new Document()));

        FulfilmentPageResponse response =
            fulfilmentService.findAll(1, "createdAt,asc", "  " + ID + "  ");

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items()).containsExactly(item);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(queryCaptor.capture(), eq(Fulfilment.class));
        assertThat(queryCaptor.getValue().getQueryObject().getString("_id")).isEqualTo(ID);

        ArgumentCaptor<Aggregation> aggregationCaptor =
            ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(
            aggregationCaptor.capture(),
            eq(Fulfilment.class),
            eq(FulfilmentPageResponse.Item.class));
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
        when(mongoTemplate.count(any(Query.class), eq(Fulfilment.class)))
            .thenReturn(0L);
        when(mongoTemplate.getCollectionName(
            uk.gov.defra.trade.imports.animals.notification.Notification.class))
            .thenReturn("notification");
        when(mongoTemplate.aggregate(
            any(Aggregation.class),
            eq(Fulfilment.class),
            eq(FulfilmentPageResponse.Item.class)))
            .thenReturn(new AggregationResults<>(List.of(), new Document()));

        FulfilmentPageResponse response =
            fulfilmentService.findAll(1, null, "GBN-AG-26-ZZZZZZ");

        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.items()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FulfilmentStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void copy_shouldCreateDraftFromCopyableStatus(FulfilmentStatus sourceStatus) {
        String copyId = "GBN-AG-26-ABC124";
        String idempotencyKey = "copy-key";
        List<Document> sourceContent = List.of(new Document("value", sourceStatus.name()));
        Fulfilment source = fulfilment(sourceStatus, sourceContent, List.of());
        when(fulfilmentRepository.findByCopyIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(source));
        when(referenceNumberGenerator.generate()).thenReturn(copyId);
        when(fulfilmentRepository.insert(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment copy = fulfilmentService.copy(ID, idempotencyKey);

        assertThat(copy.getId()).isEqualTo(copyId).isNotEqualTo(ID);
        assertThat(copy.getStatus()).isEqualTo(FulfilmentStatus.DRAFT);
        assertThat(copy.getCreatedAt()).isNotNull();
        assertThat(copy.getSubmittedAt()).isNull();
        assertThat(copy.getSubmittedFulfilment()).isNull();
        assertThat(copy.getFulfilment())
            .isEqualTo(sourceContent)
            .isNotSameAs(sourceContent);
        assertThat(copy.getCopyIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    void copy_shouldReturnExistingCopyForSameIdempotencyKey() {
        String idempotencyKey = "copy-key";
        Fulfilment existingCopy = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findByCopyIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.of(existingCopy));

        Fulfilment result = fulfilmentService.copy(ID, idempotencyKey);

        assertThat(result).isSameAs(existingCopy);
        verify(fulfilmentRepository, never()).findById(any());
        verify(fulfilmentRepository, never()).insert(any(Fulfilment.class));
        verify(referenceNumberGenerator, never()).generate();
    }

    @Test
    void copy_shouldRejectDeletedSource() {
        String idempotencyKey = "copy-key";
        Fulfilment deleted = fulfilment(FulfilmentStatus.DELETED, List.of(), null);
        when(fulfilmentRepository.findByCopyIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> fulfilmentService.copy(ID, idempotencyKey))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot copy fulfilment with status: DELETED");

        verify(fulfilmentRepository, never()).insert(any(Fulfilment.class));
    }

    @Test
    void copy_shouldRejectBlankIdempotencyKey() {
        assertThatThrownBy(() -> fulfilmentService.copy(ID, " "))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Idempotency-Key must not be blank");

        verify(fulfilmentRepository, never()).findById(any());
        verify(fulfilmentRepository, never()).insert(any(Fulfilment.class));
    }

    @ParameterizedTest
    @EnumSource(value = FulfilmentStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void softDelete_shouldSetDeletedForDeletableStatus(FulfilmentStatus sourceStatus) {
        Fulfilment fulfilment = fulfilment(sourceStatus, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment deleted = fulfilmentService.softDelete(ID);

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        verify(fulfilmentRepository).save(fulfilment);
    }

    @Test
    void softDelete_shouldReturnAlreadyDeletedUnchanged() {
        Fulfilment deleted = fulfilment(FulfilmentStatus.DELETED, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(deleted));

        Fulfilment result = fulfilmentService.softDelete(ID);

        assertThat(result).isSameAs(deleted);
        verify(fulfilmentRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void amend_shouldCaptureSnapshotThatIsUnaffectedByReplace() {
        List<Document> submittedContent = List.of(new Document("value", "submitted"));
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.SUBMITTED, submittedContent, null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment amended = fulfilmentService.amend(ID, TRACE_ID, null);

        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.AMEND);
        assertThat(amended.getSubmittedFulfilment())
            .isEqualTo(submittedContent)
            .isNotSameAs(submittedContent);
        assertThat(amended.getSubmittedAt()).isNull();

        List<Document> amendedContent = List.of(new Document("value", "amended"));
        fulfilmentService.replace(
            ID,
            FulfilmentDto.builder().id(ID).fulfilment(amendedContent).build());

        assertThat(fulfilment.getFulfilment()).isEqualTo(amendedContent);
        assertThat(fulfilment.getSubmittedFulfilment()).isEqualTo(submittedContent);
    }

    @Test
    void cancelAmend_shouldRestoreSnapshotAndSetSubmitted() {
        List<Document> submittedContent = List.of(new Document("value", "submitted"));
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.AMEND,
            List.of(new Document("value", "amended")),
            submittedContent);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime beforeCancel = LocalDateTime.now();

        Fulfilment restored = fulfilmentService.cancelAmend(ID);

        assertThat(restored.getFulfilment()).isEqualTo(submittedContent);
        assertThat(restored.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(restored.getSubmittedFulfilment()).isNull();
        assertThat(restored.getSubmittedAt()).isAfterOrEqualTo(beforeCancel);
        verify(fulfilmentRepository).save(fulfilment);
    }

    @Test
    void cancelAmend_shouldRejectNonAmendStatus() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.SUBMITTED, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));

        assertThatThrownBy(() -> fulfilmentService.cancelAmend(ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUBMITTED");

        verify(fulfilmentRepository, never()).save(any());
    }

    @Test
    void cancelAmend_shouldRejectMissingSubmittedSnapshot() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.AMEND, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));

        assertThatThrownBy(() -> fulfilmentService.cancelAmend(ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no submitted snapshot");

        verify(fulfilmentRepository, never()).save(any());
    }

    @Test
    void submitFromAmend_shouldClearSubmittedSnapshot() {
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.AMEND,
            List.of(new Document("value", "amended")),
            List.of(new Document("value", "submitted")));
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment submitted = fulfilmentService.submit(ID, TRACE_ID, null);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(submitted.getSubmittedFulfilment()).isNull();
        assertThat(submitted.getSubmittedAt()).isNotNull();
    }

    @Test
    void submit_shouldCascadeNotificationWithTraceId() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);
        Actor actor = Actor.builder().id("contact-guid-001").build();

        Fulfilment submitted = fulfilmentService.submit(ID, TRACE_ID, actor);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        verify(notificationService).submitNotification(ID, TRACE_ID, actor);
    }

    @Test
    void amend_shouldCascadeNotificationWithTraceId() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.SUBMITTED, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);
        Actor actor = Actor.builder().id("contact-guid-002").build();

        Fulfilment amended = fulfilmentService.amend(ID, TRACE_ID, actor);

        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.AMEND);
        verify(notificationService).amendNotification(ID, TRACE_ID, actor);
    }

    @Test
    void cancelAmend_shouldCascadeNotification() {
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.AMEND, List.of(), List.of());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);

        Fulfilment submitted = fulfilmentService.cancelAmend(ID);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        verify(notificationService).cancelAmendNotification(ID);
    }

    @Test
    void softDelete_shouldCascadeNotification() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);
        when(notificationService.findByRef(ID))
            .thenReturn(NotificationResponse.builder()
                .status(NotificationStatus.DRAFT)
                .build());

        Fulfilment deleted = fulfilmentService.softDelete(ID);

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        verify(notificationService).softDeleteNotification(ID);
    }

    @Test
    void submit_shouldSucceedWithoutNotificationProjection() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(false);

        Fulfilment submitted = fulfilmentService.submit(ID, TRACE_ID, null);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        verify(notificationService, never()).submitNotification(any(), any(), any());
        verify(notificationService, never()).amendNotification(any(), any(), any());
        verify(notificationService, never()).cancelAmendNotification(any());
        verify(notificationService, never()).softDeleteNotification(any());
    }

    @Test
    void softDelete_shouldSkipAlreadyDeletedNotificationProjection() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);
        when(notificationService.findByRef(ID))
            .thenReturn(NotificationResponse.builder()
                .status(NotificationStatus.DELETED)
                .build());

        Fulfilment deleted = fulfilmentService.softDelete(ID);

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        verify(notificationService, never()).softDeleteNotification(any());
    }

    private Fulfilment fulfilment(
        FulfilmentStatus status,
        List<Document> content,
        List<Document> submittedContent) {
        return Fulfilment.builder()
            .id(ID)
            .fulfilment(content)
            .submittedFulfilment(submittedContent)
            .status(status)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
