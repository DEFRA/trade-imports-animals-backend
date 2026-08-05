package uk.gov.defra.trade.imports.plantproducts.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.plantproducts.PlantProductsNotificationTestData.refNumber;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsBadRequestException;
import uk.gov.defra.trade.imports.plantproducts.exceptions.PlantProductsNotFoundException;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotification;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationRepository;
import uk.gov.defra.trade.imports.plantproducts.notification.PlantProductsNotificationStatus;

@ExtendWith(MockitoExtension.class)
class PlantProductsAccompanyingDocumentServiceTest {

    private static final String REFERENCE = "GBN-PP-26-ABC001";
    private static final String DOCUMENT_ID = "document-001";

    @Mock
    private PlantProductsAccompanyingDocumentRepository documentRepository;

    @Mock
    private PlantProductsNotificationRepository notificationRepository;

    @Mock
    private PlantProductsAccompanyingDocumentMapper documentMapper;

    private PlantProductsAccompanyingDocumentService service;

    @BeforeEach
    void setUp() {
        service = new PlantProductsAccompanyingDocumentService(
            documentRepository, notificationRepository, documentMapper);
        lenient().when(documentRepository.save(any(PlantProductsAccompanyingDocument.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    class ListDocuments {

        @Test
        void list_shouldReturnRepositoryDocumentsForKnownNotification() {
            // Given
            PlantProductsAccompanyingDocument document = document();
            stubNotification(PlantProductsNotificationStatus.DRAFT);
            when(documentRepository.findByNotificationReferenceNumber(REFERENCE))
                .thenReturn(List.of(document));

            // When
            List<PlantProductsAccompanyingDocument> result = service.list(REFERENCE);

            // Then
            assertThat(result).containsExactly(document);
        }

        @Test
        void list_shouldThrowNotFoundForUnknownNotification() {
            // Given
            when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.list(REFERENCE))
                .isInstanceOf(PlantProductsNotFoundException.class);
        }
    }

    @Nested
    class Create {

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"DRAFT", "AMEND"})
        void create_shouldUsePathReferenceAndStampTimestampsWhenWritable(
            PlantProductsNotificationStatus status) {
            // Given
            stubNotification(status);
            PlantProductsAccompanyingDocument mapped = document();
            mapped.setNotificationReferenceNumber(refNumber("B0DY01"));
            when(documentMapper.toEntity(dto())).thenReturn(mapped);
            LocalDateTime start = LocalDateTime.now();

            // When
            PlantProductsAccompanyingDocument result = service.create(REFERENCE, dto());

            // Then
            assertThat(result.getNotificationReferenceNumber()).isEqualTo(REFERENCE);
            assertThat(result.getCreated()).isAfterOrEqualTo(start);
            assertThat(result.getUpdated()).isAfterOrEqualTo(start);
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"SUBMITTED", "DELETED"})
        void create_shouldRejectNonWritableNotification(PlantProductsNotificationStatus status) {
            // Given
            stubNotification(status);

            // When & Then
            assertThatThrownBy(() -> service.create(REFERENCE, dto()))
                .isInstanceOf(PlantProductsBadRequestException.class);
            verify(documentRepository, never()).save(any());
        }
    }

    @Nested
    class Replace {

        @Test
        void replace_shouldUseScopedLookupAndRestampUpdated() {
            // Given
            stubNotification(PlantProductsNotificationStatus.AMEND);
            PlantProductsAccompanyingDocument existing = document();
            LocalDateTime oldUpdated = LocalDateTime.of(2026, 1, 1, 0, 0);
            existing.setUpdated(oldUpdated);
            when(documentRepository.findByIdAndNotificationReferenceNumber(DOCUMENT_ID, REFERENCE))
                .thenReturn(Optional.of(existing));

            // When
            PlantProductsAccompanyingDocument result = service.replace(REFERENCE, DOCUMENT_ID, dto());

            // Then
            assertThat(result.getDocumentType()).isEqualTo("CHEDPP_PHYTO");
            assertThat(result.getDocumentReference()).isEqualTo("PHYTO-BR-001");
            assertThat(result.getUpdated()).isAfter(oldUpdated);
            verify(documentRepository)
                .findByIdAndNotificationReferenceNumber(DOCUMENT_ID, REFERENCE);
        }

        @Test
        void replace_shouldThrowNotFoundWhenDocumentIsNotUnderNotification() {
            // Given
            stubNotification(PlantProductsNotificationStatus.DRAFT);
            when(documentRepository.findByIdAndNotificationReferenceNumber(DOCUMENT_ID, REFERENCE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.replace(REFERENCE, DOCUMENT_ID, dto()))
                .isInstanceOf(PlantProductsNotFoundException.class);
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"SUBMITTED", "DELETED"})
        void replace_shouldRejectNonWritableNotification(PlantProductsNotificationStatus status) {
            // Given
            stubNotification(status);

            // When & Then
            assertThatThrownBy(() -> service.replace(REFERENCE, DOCUMENT_ID, dto()))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_shouldRemoveDocumentFoundByScopedLookup() {
            // Given
            stubNotification(PlantProductsNotificationStatus.DRAFT);
            PlantProductsAccompanyingDocument existing = document();
            when(documentRepository.findByIdAndNotificationReferenceNumber(DOCUMENT_ID, REFERENCE))
                .thenReturn(Optional.of(existing));

            // When
            service.delete(REFERENCE, DOCUMENT_ID);

            // Then
            verify(documentRepository).delete(existing);
        }

        @Test
        void delete_shouldThrowNotFoundWhenDocumentIsAbsent() {
            // Given
            stubNotification(PlantProductsNotificationStatus.DRAFT);
            when(documentRepository.findByIdAndNotificationReferenceNumber(DOCUMENT_ID, REFERENCE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.delete(REFERENCE, DOCUMENT_ID))
                .isInstanceOf(PlantProductsNotFoundException.class);
        }

        @ParameterizedTest
        @EnumSource(value = PlantProductsNotificationStatus.class, names = {"SUBMITTED", "DELETED"})
        void delete_shouldRejectNonWritableNotification(PlantProductsNotificationStatus status) {
            // Given
            stubNotification(status);

            // When & Then
            assertThatThrownBy(() -> service.delete(REFERENCE, DOCUMENT_ID))
                .isInstanceOf(PlantProductsBadRequestException.class);
        }
    }

    private void stubNotification(PlantProductsNotificationStatus status) {
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(PlantProductsNotification.builder().status(status).build()));
    }

    private static PlantProductsAccompanyingDocumentDto dto() {
        return new PlantProductsAccompanyingDocumentDto(
            DOCUMENT_ID,
            "CHEDPP_PHYTO",
            "PHYTO-BR-001",
            LocalDate.of(2026, 7, 30),
            List.of(DocumentFile.builder().fileId("file-001").filename("certificate.pdf").build()));
    }

    private static PlantProductsAccompanyingDocument document() {
        return PlantProductsAccompanyingDocument.builder()
            .id(DOCUMENT_ID)
            .notificationReferenceNumber(REFERENCE)
            .documentType("OLD")
            .documentReference("OLD-REF")
            .issueDate(LocalDate.of(2026, 7, 1))
            .files(List.of())
            .created(LocalDateTime.of(2026, 7, 1, 10, 0))
            .updated(LocalDateTime.of(2026, 7, 1, 10, 0))
            .build();
    }
}
