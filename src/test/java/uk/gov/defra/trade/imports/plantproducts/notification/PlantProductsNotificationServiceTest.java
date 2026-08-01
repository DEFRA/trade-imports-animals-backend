package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedDto;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.fullyPopulatedNotification;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.refNumber;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uk.gov.defra.trade.imports.plantproducts.accompanyingdocument.PlantProductsAccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.plantproducts.configuration.PlantProductsNotificationTtlConfig;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsBadRequestException;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;

@ExtendWith(MockitoExtension.class)
class PlantProductsNotificationServiceTest {

    private static final List<PlantProductsNotificationStatus> VISIBLE_STATUSES = List.of(
        PlantProductsNotificationStatus.DRAFT,
        PlantProductsNotificationStatus.SUBMITTED,
        PlantProductsNotificationStatus.AMEND);

    @Mock
    private PlantProductsNotificationRepository notificationRepository;

    @Mock
    private PlantProductsAccompanyingDocumentRepository accompanyingDocumentRepository;

    @Mock
    private PlantProductsNotificationMapper notificationMapper;

    @Mock
    private PlantProductsNotificationCopyMapper notificationCopyMapper;

    @Mock
    private PlantProductsReferenceNumberGenerator referenceNumberGenerator;

    @Mock
    private PlantProductsNotificationTtlConfig ttlConfig;

    private PlantProductsNotificationService service;

    @BeforeEach
    void setUp() {
        service = new PlantProductsNotificationService(
            notificationRepository,
            accompanyingDocumentRepository,
            notificationMapper,
            notificationCopyMapper,
            referenceNumberGenerator,
            ttlConfig,
            25);
        lenient().when(ttlConfig.days()).thenReturn(7);
        lenient().when(ttlConfig.isProd()).thenReturn(false);
        lenient().when(notificationRepository.save(any(PlantProductsNotification.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    class Create {

        @Test
        void create_shouldRejectBodyReferenceNumber() {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(refNumber("B0DY01"));

            // When & Then
            assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(PlantProductsBadRequestException.class);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        void create_shouldStampServerFieldsMintReferenceAndApplyContent() {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            String mintedReference = refNumber("CRT001");
            when(referenceNumberGenerator.generate()).thenReturn(mintedReference);
            LocalDateTime start = LocalDateTime.now();

            // When
            PlantProductsNotification result = service.create(dto);

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(mintedReference);
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.DRAFT);
            assertThat(result.getChedType()).isEqualTo("CHEDPP");
            assertThat(result.getOwnership().getAssignedOrganisationId()).isEqualTo("stub-org");
            assertThat(result.getOwnership().getAssignedOrganisationName()).isEqualTo("Stubbed organisation");
            assertThat(result.getCreated()).isAfterOrEqualTo(start);
            assertThat(result.getUpdated()).isAfterOrEqualTo(start);
            assertThat(result.getExpireAt()).isEqualTo(result.getCreated().plusDays(7));
            verify(notificationMapper).applyContent(dto, result);
        }

        @Test
        void create_shouldMintFreshReferenceAndSucceedAfterCollision() {
            // Given
            when(referenceNumberGenerator.generate()).thenReturn(
                refNumber("C0M001"), refNumber("C0M002"));
            when(notificationRepository.save(any(PlantProductsNotification.class)))
                .thenThrow(new DuplicateKeyException("collision"))
                .thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PlantProductsNotification result = service.create(fullyPopulatedDto());

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(refNumber("C0M002"));
            verify(referenceNumberGenerator, times(2)).generate();
            verify(notificationRepository, times(2)).save(any());
        }

        @Test
        void create_shouldRetryThreeTimesThenFail_whenEveryMintCollides() {
            // Given
            when(referenceNumberGenerator.generate()).thenReturn(
                refNumber("C0M001"), refNumber("C0M002"), refNumber("C0M003"));
            when(notificationRepository.save(any(PlantProductsNotification.class)))
                .thenThrow(new DuplicateKeyException("collision"));

            // When & Then
            assertThatThrownBy(() -> service.create(fullyPopulatedDto()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");
            verify(referenceNumberGenerator, times(3)).generate();
            verify(notificationRepository, times(3)).save(any());
        }
    }

    @Nested
    class Replace {

        @Test
        void replace_shouldRejectPathBodyMismatch() {
            // Given
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(refNumber("B0DY01"));

            // When & Then
            assertThatThrownBy(() -> service.replace(refNumber("PATH01"), dto))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }

        @Test
        void replace_shouldCreateWhenReferenceIsUnknown() {
            // Given
            String reference = refNumber("VPS001");
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(reference);
            when(notificationRepository.findByReferenceNumber(reference)).thenReturn(Optional.empty());

            // When
            PlantProductsNotificationService.ReplaceResult result = service.replace(reference, dto);

            // Then
            assertThat(result.created()).isTrue();
            assertThat(result.notification().getReferenceNumber()).isEqualTo(reference);
            assertThat(result.notification().getChedType()).isEqualTo("CHEDPP");
            assertThat(result.notification().getStatus()).isEqualTo(PlantProductsNotificationStatus.DRAFT);
            assertThat(result.notification().getOwnership().getAssignedOrganisationId()).isEqualTo("stub-org");
            verify(notificationMapper).applyContent(dto, result.notification());
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"SUBMITTED", "DELETED"})
        void replace_shouldRejectNonWritableNotification(PlantProductsNotificationStatus status) {
            // Given
            String reference = refNumber("M0CK01");
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(reference);
            PlantProductsNotification existing = PlantProductsNotification.builder().status(status).build();
            when(notificationRepository.findByReferenceNumber(reference)).thenReturn(Optional.of(existing));

            // When & Then
            assertThatThrownBy(() -> service.replace(reference, dto))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"DRAFT", "AMEND"})
        void replace_shouldApplyContentAndRestampUpdatedWhenWritable(
            PlantProductsNotificationStatus status) {
            // Given
            String reference = refNumber("RPM001");
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            dto.setReferenceNumber(reference);
            PlantProductsNotification existing = PlantProductsNotification.builder()
                .referenceNumber(reference)
                .status(status)
                .updated(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
            when(notificationRepository.findByReferenceNumber(reference)).thenReturn(Optional.of(existing));

            // When
            PlantProductsNotificationService.ReplaceResult result = service.replace(reference, dto);

            // Then
            assertThat(result.created()).isFalse();
            assertThat(result.notification().getUpdated()).isAfter(LocalDateTime.of(2026, 1, 1, 0, 0));
            verify(notificationMapper).applyContent(dto, existing);
        }
    }

    @Nested
    class Find {

        @Test
        void find_shouldDelegateToRepository() {
            // Given
            String reference = refNumber("FND001");
            PlantProductsNotification notification = fullyPopulatedNotification();
            when(notificationRepository.findByReferenceNumber(reference))
                .thenReturn(Optional.of(notification));

            // When
            Optional<PlantProductsNotification> result = service.find(reference);

            // Then
            assertThat(result).containsSame(notification);
        }
    }

    @Nested
    class FindAll {

        @Test
        void findAll_shouldUseOneBasedPageConfiguredSizeSortAndVisibleStatuses() {
            // Given
            PlantProductsNotification entity = fullyPopulatedNotification();
            PlantProductsNotificationDto dto = fullyPopulatedDto();
            when(notificationMapper.toDto(entity)).thenReturn(dto);
            when(notificationRepository.findAllByStatusIn(eq(VISIBLE_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), org.springframework.data.domain.PageRequest.of(1, 25), 26));

            // When
            PlantProductsNotificationPageResponse result = service.findAll(2, "createdAt,asc", null);

            // Then
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findAllByStatusIn(eq(VISIBLE_STATUSES), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
            assertThat(pageable.getValue().getSort().getOrderFor("created").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
            assertThat(result.content()).containsExactly(dto);
            assertThat(result.page()).isEqualTo(2);
            assertThat(result.pageSize()).isEqualTo(25);
            assertThat(result.totalElements()).isEqualTo(26);
            assertThat(result.totalPages()).isEqualTo(2);
        }

        @Test
        void findAll_shouldTrimAndExactMatchReferenceNumber() {
            // Given
            String reference = refNumber("0NE001");
            PlantProductsNotification entity = fullyPopulatedNotification();
            when(notificationRepository.findByReferenceNumberAndStatusIn(reference, VISIBLE_STATUSES))
                .thenReturn(Optional.of(entity));
            when(notificationMapper.toDto(entity)).thenReturn(fullyPopulatedDto());

            // When
            PlantProductsNotificationPageResponse result =
                service.findAll(1, null, "  " + reference + "  ");

            // Then
            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1);
            verify(notificationRepository, never()).findAllByStatusIn(anyList(), any());
        }

        @Test
        void findAll_shouldReturnEmptyPageWhenExactReferenceIsAbsent() {
            // Given
            String reference = refNumber("N0NE01");
            when(notificationRepository.findByReferenceNumberAndStatusIn(reference, VISIBLE_STATUSES))
                .thenReturn(Optional.empty());

            // When
            PlantProductsNotificationPageResponse result = service.findAll(1, null, reference);

            // Then
            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
        }
    }

    @Nested
    class ChangeStatus {

        @Test
        void changeStatus_shouldCaptureBaselineWhenDraftIsSubmitted() {
            // Given
            PlantProductsNotification notification = withStatus(PlantProductsNotificationStatus.DRAFT);
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.SUBMITTED, null));

            // Then
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.SUBMITTED);
            assertThat(result.getSubmittedBaseline()).isNotNull();
            assertThat(result.getSubmittedBaseline().getOrigin().getCountryCode()).isEqualTo("BR");
        }

        @Test
        void changeStatus_shouldCaptureBaselineWhenSubmittedEntersAmend() {
            // Given
            PlantProductsNotification notification = withStatus(PlantProductsNotificationStatus.SUBMITTED);
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.AMEND, false));

            // Then
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.AMEND);
            assertThat(result.getSubmittedBaseline().getOrigin().getCountryCode()).isEqualTo("BR");
        }

        @ParameterizedTest
        @MethodSource("uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationServiceTest#falseDiscardValues")
        void changeStatus_shouldRecaptureAmendedContentWhenAmendIsSubmitted(Boolean discardChanges) {
            // Given
            PlantProductsNotification notification = withStatus(PlantProductsNotificationStatus.AMEND);
            PlantProductsNotification old = fullyPopulatedNotification();
            old.getOrigin().setCountryCode("CL");
            notification.setSubmittedBaseline(PlantProductsNotificationContentSnapshot.from(old));
            notification.getOrigin().setCountryCode("PE");
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.SUBMITTED, discardChanges));

            // Then
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.SUBMITTED);
            assertThat(result.getSubmittedBaseline().getOrigin().getCountryCode()).isEqualTo("PE");
        }

        @Test
        void changeStatus_shouldRestoreContentAndKeepOldBaselineWhenAmendIsCancelled() {
            // Given
            PlantProductsNotification baselineSource = fullyPopulatedNotification();
            PlantProductsNotificationContentSnapshot baseline =
                PlantProductsNotificationContentSnapshot.from(baselineSource);
            PlantProductsNotification notification = withStatus(PlantProductsNotificationStatus.AMEND);
            notification.setSubmittedBaseline(baseline);
            notification.getOrigin().setCountryCode("PE");
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.SUBMITTED, true));

            // Then
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.SUBMITTED);
            assertThat(result.getOrigin().getCountryCode()).isEqualTo("BR");
            assertThat(result.getSubmittedBaseline()).isSameAs(baseline);
        }

        @Test
        void changeStatus_shouldDiscardItemAddedDuringAmendWhenAmendIsCancelled() {
            // Given
            PlantProductsNotification baselineSource = fullyPopulatedNotification();
            PlantProductsNotificationContentSnapshot baseline =
                PlantProductsNotificationContentSnapshot.from(baselineSource);
            PlantProductsNotification notification = withStatus(PlantProductsNotificationStatus.AMEND);
            notification.setSubmittedBaseline(baseline);
            notification.getCommodity().setCommodityComplement(List.of(
                notification.getCommodity().getCommodityComplement().getFirst(),
                CommodityLine.builder().uniqueComplementId("added-during-amend").build()));
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.SUBMITTED, true));

            // Then
            assertThat(result.getCommodity().getCommodityComplement())
                .hasSameSizeAs(baseline.getCommodity().getCommodityComplement())
                .noneMatch(line -> "added-during-amend".equals(line.getUniqueComplementId()));
            assertThat(result.getSubmittedBaseline()).isSameAs(baseline);
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"DRAFT", "SUBMITTED", "AMEND"})
        void changeStatus_shouldSoftDeleteFromEveryActiveStatus(PlantProductsNotificationStatus sourceStatus) {
            // Given
            PlantProductsNotification notification = withStatus(sourceStatus);
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.DELETED, false));

            // Then
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.DELETED);
        }

        @Test
        void changeStatus_shouldTreatDeletedToDeletedAsIdempotent() {
            // Given
            PlantProductsNotification notification = withStatus(PlantProductsNotificationStatus.DELETED);
            stubFound(notification);

            // When
            PlantProductsNotification result = service.changeStatus(
                notification.getReferenceNumber(),
                new StatusChangeRequest(PlantProductsNotificationStatus.DELETED, false));

            // Then
            assertThat(result).isSameAs(notification);
            verify(notificationRepository, never()).save(any());
        }

        @ParameterizedTest
        @MethodSource("uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationServiceTest#illegalTransitions")
        void changeStatus_shouldRejectEveryTransitionOutsideTable(
            PlantProductsNotificationStatus from, PlantProductsNotificationStatus to) {
            // Given
            PlantProductsNotification notification = withStatus(from);
            stubFound(notification);

            // When & Then
            assertThatThrownBy(() -> service.changeStatus(
                notification.getReferenceNumber(), new StatusChangeRequest(to, false)))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }

        @ParameterizedTest
        @MethodSource("uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationServiceTest#misplacedDiscardTransitions")
        void changeStatus_shouldRejectDiscardChangesOutsideCancelAmend(
            PlantProductsNotificationStatus from, PlantProductsNotificationStatus to) {
            // Given
            PlantProductsNotification notification = withStatus(from);
            stubFound(notification);

            // When & Then
            assertThatThrownBy(() -> service.changeStatus(
                notification.getReferenceNumber(), new StatusChangeRequest(to, true)))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }

        @Test
        void changeStatus_shouldThrowNotFoundForUnknownReference() {
            // Given
            String reference = refNumber("M1SS01");
            when(notificationRepository.findByReferenceNumber(reference)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.changeStatus(
                reference, new StatusChangeRequest(PlantProductsNotificationStatus.SUBMITTED, false)))
                .isInstanceOf(PlantProductsNotFoundException.class);
        }
    }

    @Nested
    class Copy {

        @Test
        void copy_shouldDelegateCopyRemintDraftAndNeverWriteDocuments() {
            // Given
            PlantProductsNotification source = withStatus(PlantProductsNotificationStatus.SUBMITTED);
            LocalDateTime copyTime = LocalDateTime.now();
            PlantProductsNotification copied = PlantProductsNotification.builder()
                .status(PlantProductsNotificationStatus.DRAFT)
                .created(copyTime)
                .updated(copyTime)
                .build();
            stubFound(source);
            when(notificationCopyMapper.copyFrom(source)).thenReturn(copied);
            when(referenceNumberGenerator.generate()).thenReturn(refNumber("C0PY01"));

            // When
            PlantProductsNotification result = service.copy(source.getReferenceNumber());

            // Then
            assertThat(result.getReferenceNumber()).isEqualTo(refNumber("C0PY01"));
            assertThat(result.getStatus()).isEqualTo(PlantProductsNotificationStatus.DRAFT);
            assertThat(result.getChedType()).isEqualTo("CHEDPP");
            assertThat(result.getCreated()).isNotNull();
            assertThat(result.getUpdated()).isNotNull();
            verify(notificationCopyMapper).copyFrom(source);
            verifyNoInteractions(accompanyingDocumentRepository);
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"DRAFT", "DELETED"})
        void copy_shouldRejectNonCopyableStatuses(PlantProductsNotificationStatus status) {
            // Given
            PlantProductsNotification source = withStatus(status);
            stubFound(source);

            // When & Then
            assertThatThrownBy(() -> service.copy(source.getReferenceNumber()))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }

        @Test
        void copy_shouldThrowNotFoundForUnknownReference() {
            // Given
            String reference = refNumber("M1SS02");
            when(notificationRepository.findByReferenceNumber(reference)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.copy(reference))
                .isInstanceOf(PlantProductsNotFoundException.class);
        }

        @Test
        void copy_shouldRetryThreeTimesThenFail_whenEveryMintCollides() {
            // Given
            PlantProductsNotification source = withStatus(PlantProductsNotificationStatus.AMEND);
            stubFound(source);
            LocalDateTime copyTime = LocalDateTime.now();
            when(notificationCopyMapper.copyFrom(source)).thenReturn(PlantProductsNotification.builder()
                .status(PlantProductsNotificationStatus.DRAFT)
                .created(copyTime)
                .updated(copyTime)
                .build());
            when(referenceNumberGenerator.generate()).thenReturn(
                refNumber("CPY001"), refNumber("CPY002"), refNumber("CPY003"));
            when(notificationRepository.save(any())).thenThrow(new DuplicateKeyException("collision"));

            // When & Then
            assertThatThrownBy(() -> service.copy(source.getReferenceNumber()))
                .isInstanceOf(IllegalStateException.class);
            verify(referenceNumberGenerator, times(3)).generate();
            verify(notificationRepository, times(3)).save(any());
        }
    }

    @Nested
    class DeleteExpired {

        @Test
        void deleteExpired_shouldBoundBatchAndCascadeEachReference() {
            // Given
            PlantProductsNotificationReferenceOnly first = () -> refNumber("EXP001");
            PlantProductsNotificationReferenceOnly second = () -> refNumber("EXP002");
            when(notificationRepository.findExpired(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));

            // When
            int result = service.deleteExpired(5);

            // Then
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationRepository).findExpired(any(LocalDateTime.class), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
            assertThat(result).isEqualTo(2);
            verify(notificationRepository).deleteAllByReferenceNumberIn(
                List.of(refNumber("EXP001"), refNumber("EXP002")));
            verify(accompanyingDocumentRepository)
                .deleteByNotificationReferenceNumber(refNumber("EXP001"));
            verify(accompanyingDocumentRepository)
                .deleteByNotificationReferenceNumber(refNumber("EXP002"));
        }

        @Test
        void deleteExpired_shouldPerformNoDeletesWhenNothingIsDue() {
            // Given
            when(notificationRepository.findExpired(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

            // When
            int result = service.deleteExpired(5);

            // Then
            assertThat(result).isZero();
            verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
            verify(accompanyingDocumentRepository, never()).deleteByNotificationReferenceNumber(any());
        }
    }

    static Stream<Arguments> falseDiscardValues() {
        return Stream.of(Arguments.of((Boolean) null), Arguments.of(false));
    }

    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
            Arguments.of(PlantProductsNotificationStatus.DRAFT, PlantProductsNotificationStatus.DRAFT),
            Arguments.of(PlantProductsNotificationStatus.DRAFT, PlantProductsNotificationStatus.AMEND),
            Arguments.of(PlantProductsNotificationStatus.SUBMITTED, PlantProductsNotificationStatus.DRAFT),
            Arguments.of(PlantProductsNotificationStatus.SUBMITTED, PlantProductsNotificationStatus.SUBMITTED),
            Arguments.of(PlantProductsNotificationStatus.AMEND, PlantProductsNotificationStatus.DRAFT),
            Arguments.of(PlantProductsNotificationStatus.AMEND, PlantProductsNotificationStatus.AMEND),
            Arguments.of(PlantProductsNotificationStatus.DELETED, PlantProductsNotificationStatus.DRAFT),
            Arguments.of(PlantProductsNotificationStatus.DELETED, PlantProductsNotificationStatus.SUBMITTED),
            Arguments.of(PlantProductsNotificationStatus.DELETED, PlantProductsNotificationStatus.AMEND));
    }

    static Stream<Arguments> misplacedDiscardTransitions() {
        return Stream.of(PlantProductsNotificationStatus.values())
            .flatMap(from -> Stream.of(PlantProductsNotificationStatus.values())
                .filter(to -> from != PlantProductsNotificationStatus.AMEND
                    || to != PlantProductsNotificationStatus.SUBMITTED)
                .map(to -> Arguments.of(from, to)));
    }

    private void stubFound(PlantProductsNotification notification) {
        when(notificationRepository.findByReferenceNumber(notification.getReferenceNumber()))
            .thenReturn(Optional.of(notification));
    }

    private static PlantProductsNotification withStatus(PlantProductsNotificationStatus status) {
        PlantProductsNotification notification = fullyPopulatedNotification();
        notification.setStatus(status);
        return notification;
    }
}
