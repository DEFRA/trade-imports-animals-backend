package uk.gov.defra.trade.imports.animals.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.animals.ownership.Owner;

@ExtendWith(MockitoExtension.class)
class FulfilmentServiceTest {

    private static final String ID = "GBN-AG-26-ABC123";
    private static final Owner OWNER = new Owner("owner-id", "owner-organisation");

    @Mock
    private FulfilmentRepository fulfilmentRepository;

    @Mock
    private ReferenceNumberGenerator referenceNumberGenerator;

    private FulfilmentService fulfilmentService;

    @BeforeEach
    void setUp() {
        fulfilmentService =
            new FulfilmentService(fulfilmentRepository, referenceNumberGenerator, 20);
    }

    @Test
    void amend_shouldCaptureSnapshotThatIsUnaffectedByReplace() {
        List<Document> submittedContent = List.of(new Document("value", "submitted"));
        Fulfilment fulfilment = fulfilment(
            FulfilmentStatus.SUBMITTED, submittedContent, null);
        when(fulfilmentRepository.findById(ID)).thenReturn(Optional.of(fulfilment));
        when(fulfilmentRepository.save(any(Fulfilment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Fulfilment amended = fulfilmentService.amend(ID, OWNER);

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

        Fulfilment submitted = fulfilmentService.submit(ID, OWNER);

        assertThat(submitted.getStatus()).isEqualTo(FulfilmentStatus.SUBMITTED);
        assertThat(submitted.getSubmittedFulfilment()).isNull();
        assertThat(submitted.getSubmittedAt()).isNotNull();
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
