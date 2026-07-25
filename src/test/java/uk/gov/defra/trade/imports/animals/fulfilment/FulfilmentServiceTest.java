package uk.gov.defra.trade.imports.animals.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.NotificationResponse;
import uk.gov.defra.trade.imports.animals.notification.NotificationService;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

@ExtendWith(MockitoExtension.class)
class FulfilmentServiceTest {

    private static final String ID = "GBN-AG-26-ABC123";
    private static final String TRACE_ID = "trace-fulfilment-001";
    private static final Owner OWNER = new Owner("owner-id", "owner-organisation");

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

    @ParameterizedTest
    @EnumSource(value = FulfilmentStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
    void copy_shouldCreateOwnedDraftFromCopyableStatus(FulfilmentStatus sourceStatus) {
        String copyId = "GBN-AG-26-ABC124";
        String idempotencyKey = "copy-key";
        List<Document> sourceContent = List.of(new Document("value", sourceStatus.name()));
        Fulfilment source = fulfilment(sourceStatus, sourceContent, List.of());
        when(fulfilmentRepository
            .findByOwnerSubAndOwnerOrganisationAndCopyIdempotencyKey(
                OWNER.sub(), OWNER.organisation(), idempotencyKey))
            .thenReturn(Optional.empty());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(source));
        when(referenceNumberGenerator.generate()).thenReturn(copyId);
        when(fulfilmentRepository.insert(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment copy = fulfilmentService.copy(ID, OWNER, idempotencyKey);

        assertThat(copy.getId()).isEqualTo(copyId).isNotEqualTo(ID);
        assertThat(copy.getOwner()).isEqualTo(OWNER);
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
    void copy_shouldReturnExistingCopyForSameOwnerAndIdempotencyKey() {
        String idempotencyKey = "copy-key";
        Fulfilment existingCopy = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository
            .findByOwnerSubAndOwnerOrganisationAndCopyIdempotencyKey(
                OWNER.sub(), OWNER.organisation(), idempotencyKey))
            .thenReturn(Optional.of(existingCopy));

        Fulfilment result = fulfilmentService.copy(ID, OWNER, idempotencyKey);

        assertThat(result).isSameAs(existingCopy);
        verify(fulfilmentRepository, never()).findById(any());
        verify(fulfilmentRepository, never()).insert(any(Fulfilment.class));
        verify(referenceNumberGenerator, never()).generate();
    }

    @Test
    void copy_shouldRejectDeletedSource() {
        String idempotencyKey = "copy-key";
        Fulfilment deleted = fulfilment(FulfilmentStatus.DELETED, List.of(), null);
        when(fulfilmentRepository
            .findByOwnerSubAndOwnerOrganisationAndCopyIdempotencyKey(
                OWNER.sub(), OWNER.organisation(), idempotencyKey))
            .thenReturn(Optional.empty());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> fulfilmentService.copy(ID, OWNER, idempotencyKey))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot copy fulfilment with status: DELETED");

        verify(fulfilmentRepository, never()).insert(any(Fulfilment.class));
    }

    @Test
    void copy_shouldRejectBlankIdempotencyKey() {
        assertThatThrownBy(() -> fulfilmentService.copy(ID, OWNER, " "))
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

        Fulfilment deleted = fulfilmentService.softDelete(ID, OWNER);

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        verify(fulfilmentRepository).save(fulfilment);
    }

    @Test
    void softDelete_shouldReturnAlreadyDeletedUnchanged() {
        Fulfilment deleted = fulfilment(FulfilmentStatus.DELETED, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(deleted));

        Fulfilment result = fulfilmentService.softDelete(ID, OWNER);

        assertThat(result).isSameAs(deleted);
        verify(fulfilmentRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void softDelete_shouldHideFulfilmentOwnedBySomeoneElse() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        Owner differentOwner = new Owner("different-owner", "different-organisation");

        assertThatThrownBy(() -> fulfilmentService.softDelete(ID, differentOwner))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(ID);

        verify(fulfilmentRepository, never()).save(any());
    }

    @Test
    void amend_shouldCaptureSnapshotThatIsUnaffectedByReplace() {
        List<Document> submittedContent = List.of(new Document("value", "submitted"));
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.SUBMITTED, submittedContent, null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment amended = fulfilmentService.amend(ID, OWNER, TRACE_ID);

        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.AMEND);
        assertThat(amended.getSubmittedFulfilment())
            .isEqualTo(submittedContent)
            .isNotSameAs(submittedContent);
        assertThat(amended.getSubmittedAt()).isNull();

        List<Document> amendedContent = List.of(new Document("value", "amended"));
        fulfilmentService.replace(
            ID,
            FulfilmentDto.builder().id(ID).fulfilment(amendedContent).build(),
            OWNER);

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

        Fulfilment restored = fulfilmentService.cancelAmend(ID, OWNER);

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

        assertThatThrownBy(() -> fulfilmentService.cancelAmend(ID, OWNER))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUBMITTED");

        verify(fulfilmentRepository, never()).save(any());
    }

    @Test
    void cancelAmend_shouldRejectMissingSubmittedSnapshot() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.AMEND, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));

        assertThatThrownBy(() -> fulfilmentService.cancelAmend(ID, OWNER))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no submitted snapshot");

        verify(fulfilmentRepository, never()).save(any());
    }

    @Test
    void cancelAmend_shouldHideFulfilmentOwnedBySomeoneElse() {
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.AMEND, List.of(), List.of());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        Owner differentOwner = new Owner("different-owner", "different-organisation");

        assertThatThrownBy(() -> fulfilmentService.cancelAmend(ID, differentOwner))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(ID);

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

        Fulfilment submitted = fulfilmentService.submit(ID, OWNER, TRACE_ID);

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

        Fulfilment submitted = fulfilmentService.submit(ID, OWNER, TRACE_ID);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        verify(notificationService).submitNotification(ID, TRACE_ID, OWNER);
    }

    @Test
    void amend_shouldCascadeNotificationWithTraceId() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.SUBMITTED, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);

        Fulfilment amended = fulfilmentService.amend(ID, OWNER, TRACE_ID);

        assertThat(amended.getStatus()).isEqualTo(FulfilmentStatus.AMEND);
        verify(notificationService).amendNotification(ID, TRACE_ID, OWNER);
    }

    @Test
    void cancelAmend_shouldCascadeNotification() {
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.AMEND, List.of(), List.of());
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);

        Fulfilment submitted = fulfilmentService.cancelAmend(ID, OWNER);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        verify(notificationService).cancelAmendNotification(ID, OWNER);
    }

    @Test
    void softDelete_shouldCascadeNotification() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);
        when(notificationService.findByRef(ID, OWNER))
            .thenReturn(NotificationResponse.builder()
                .status(NotificationStatus.DRAFT)
                .build());

        Fulfilment deleted = fulfilmentService.softDelete(ID, OWNER);

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        verify(notificationService).softDeleteNotification(ID, OWNER);
    }

    @Test
    void submit_shouldSucceedWithoutNotificationProjection() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(false);

        Fulfilment submitted = fulfilmentService.submit(ID, OWNER, TRACE_ID);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        verify(notificationService, never()).submitNotification(any(), any(), any());
        verify(notificationService, never()).amendNotification(any(), any(), any());
        verify(notificationService, never()).cancelAmendNotification(any(), any());
        verify(notificationService, never()).softDeleteNotification(any(), any());
    }

    @Test
    void softDelete_shouldSkipAlreadyDeletedNotificationProjection() {
        Fulfilment fulfilment = fulfilment(FulfilmentStatus.DRAFT, List.of(), null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationService.existsByReferenceNumber(ID)).thenReturn(true);
        when(notificationService.findByRef(ID, OWNER))
            .thenReturn(NotificationResponse.builder()
                .status(NotificationStatus.DELETED)
                .build());

        Fulfilment deleted = fulfilmentService.softDelete(ID, OWNER);

        assertThat(deleted.getStatus()).isEqualTo(FulfilmentStatus.DELETED);
        verify(notificationService, never()).softDeleteNotification(any(), any());
    }

    private Fulfilment fulfilment(
        FulfilmentStatus status,
        List<Document> content,
        List<Document> submittedContent) {
        return Fulfilment.builder()
            .id(ID)
            .owner(OWNER)
            .fulfilment(content)
            .submittedFulfilment(submittedContent)
            .status(status)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
