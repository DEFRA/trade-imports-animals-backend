package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocumentRepository;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;

/**
 * Integration test for {@link AccompanyingDocumentRepository} derived query methods.
 *
 * <p>Uses a real Testcontainers MongoDB instance (inherited from {@link IntegrationBase}) to verify
 * that the Spring Data MongoDB derived query method names resolve to the correct MongoDB queries.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code findByUploadId} — unique index lookup by upload ID
 *   <li>{@code findFirstByNotificationReferenceNumberAndScanStatus} — compound field query
 *   <li>{@code deleteAllByNotificationReferenceNumberIn} — bulk delete by reference number list
 *   <li>Unique-index enforcement on {@code uploadId} — guards against accidental removal of
 *       {@code @Indexed(unique = true)} on the field
 * </ul>
 */
class AccompanyingDocumentRepositoryIT extends IntegrationBase {

  @Autowired
  private AccompanyingDocumentRepository repository;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
  }

  // ---------------------------------------------------------------------------
  // findByUploadId
  // ---------------------------------------------------------------------------

  /**
   * findByUploadId returns the matching document when the uploadId exists.
   */
  @Test
  void findByUploadId_shouldReturnDocument_whenUploadIdExists() {
    // Arrange
    AccompanyingDocument doc = AccompanyingDocument.builder()
        .uploadId("repo-test-upload-001")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPO001")
        .scanStatus(ScanStatus.PENDING)
        .build();
    repository.save(doc);

    // Act
    Optional<AccompanyingDocument> result = repository.findByUploadId("repo-test-upload-001");

    // Assert
    assertThat(result).isPresent();
    assertThat(result.get().getUploadId()).isEqualTo("repo-test-upload-001");
    assertThat(result.get().getNotificationReferenceNumber()).isEqualTo("DRAFT.IMP.2026.REPO001");
    assertThat(result.get().getScanStatus()).isEqualTo(ScanStatus.PENDING);
  }

  /**
   * findByUploadId returns an empty Optional when no document matches the uploadId.
   */
  @Test
  void findByUploadId_shouldReturnEmpty_whenUploadIdDoesNotExist() {
    // Act
    Optional<AccompanyingDocument> result =
        repository.findByUploadId("non-existent-repo-test-upload-id");

    // Assert
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // findFirstByNotificationReferenceNumberAndScanStatus
  // ---------------------------------------------------------------------------

  /**
   * findFirstByNotificationReferenceNumberAndScanStatus returns the matching document when one
   * exists with the given reference number and scan status.
   */
  @Test
  void findFirstByNotificationReferenceNumberAndScanStatus_shouldReturnDocument_whenMatchExists() {
    // Arrange — save one PENDING and one COMPLETE for the same notification ref
    AccompanyingDocument pending = AccompanyingDocument.builder()
        .uploadId("repo-test-upload-pending")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPO002")
        .scanStatus(ScanStatus.PENDING)
        .build();
    AccompanyingDocument complete = AccompanyingDocument.builder()
        .uploadId("repo-test-upload-complete")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPO002")
        .scanStatus(ScanStatus.COMPLETE)
        .build();
    repository.saveAll(List.of(pending, complete));

    // Act — query specifically for PENDING status
    Optional<AccompanyingDocument> result =
        repository.findFirstByNotificationReferenceNumberAndScanStatus(
            "DRAFT.IMP.2026.REPO002", ScanStatus.PENDING);

    // Assert
    assertThat(result).isPresent();
    assertThat(result.get().getUploadId()).isEqualTo("repo-test-upload-pending");
    assertThat(result.get().getScanStatus()).isEqualTo(ScanStatus.PENDING);
  }

  /**
   * findFirstByNotificationReferenceNumberAndScanStatus returns empty when the status does not
   * match any document for the given reference number.
   */
  @Test
  void findFirstByNotificationReferenceNumberAndScanStatus_shouldReturnEmpty_whenStatusNotFound() {
    // Arrange — only a COMPLETE document exists; query for PENDING (which doesn't exist)
    AccompanyingDocument complete = AccompanyingDocument.builder()
        .uploadId("repo-test-upload-complete-2")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPO003")
        .scanStatus(ScanStatus.COMPLETE)
        .build();
    repository.save(complete);

    // Act
    Optional<AccompanyingDocument> result =
        repository.findFirstByNotificationReferenceNumberAndScanStatus(
            "DRAFT.IMP.2026.REPO003", ScanStatus.PENDING);

    // Assert
    assertThat(result).isEmpty();
  }

  /**
   * findFirstByNotificationReferenceNumberAndScanStatus returns empty when the notification
   * reference number does not match any stored document.
   */
  @Test
  void findFirstByNotificationReferenceNumberAndScanStatus_shouldReturnEmpty_whenRefNotFound() {
    // Arrange
    AccompanyingDocument doc = AccompanyingDocument.builder()
        .uploadId("repo-test-upload-003")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPO004")
        .scanStatus(ScanStatus.PENDING)
        .build();
    repository.save(doc);

    // Act
    Optional<AccompanyingDocument> result =
        repository.findFirstByNotificationReferenceNumberAndScanStatus(
            "DRAFT.IMP.2026.REPO.UNKNOWN", ScanStatus.PENDING);

    // Assert
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // deleteAllByNotificationReferenceNumberIn
  // ---------------------------------------------------------------------------

  /**
   * deleteAllByNotificationReferenceNumberIn removes all documents whose notification reference
   * number is in the supplied list, leaving documents with other reference numbers intact.
   */
  @Test
  void deleteAllByNotificationReferenceNumberIn_shouldDeleteMatchingDocuments() {
    // Arrange — three documents; two share a reference targeted for deletion
    AccompanyingDocument docA1 = AccompanyingDocument.builder()
        .uploadId("repo-test-del-A1")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPA")
        .scanStatus(ScanStatus.PENDING)
        .build();
    AccompanyingDocument docA2 = AccompanyingDocument.builder()
        .uploadId("repo-test-del-A2")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPA")
        .scanStatus(ScanStatus.COMPLETE)
        .build();
    AccompanyingDocument docB1 = AccompanyingDocument.builder()
        .uploadId("repo-test-del-B1")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPB")
        .scanStatus(ScanStatus.PENDING)
        .build();
    repository.saveAll(List.of(docA1, docA2, docB1));

    // Act — delete everything under "REPA"
    repository.deleteAllByNotificationReferenceNumberIn(List.of("DRAFT.IMP.2026.REPA"));

    // Assert — REPA documents are gone; REPB document remains
    List<AccompanyingDocument> remaining = repository.findAll();
    assertThat(remaining).hasSize(1);
    assertThat(remaining.get(0).getUploadId()).isEqualTo("repo-test-del-B1");
  }

  /**
   * deleteAllByNotificationReferenceNumberIn deletes documents matching any reference number in
   * the list when multiple reference numbers are provided.
   */
  @Test
  void deleteAllByNotificationReferenceNumberIn_shouldDeleteAcrossMultipleRefs() {
    // Arrange
    AccompanyingDocument docE = AccompanyingDocument.builder()
        .uploadId("repo-test-del-E")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPE")
        .scanStatus(ScanStatus.PENDING)
        .build();
    AccompanyingDocument docF = AccompanyingDocument.builder()
        .uploadId("repo-test-del-F")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPF")
        .scanStatus(ScanStatus.PENDING)
        .build();
    AccompanyingDocument docG = AccompanyingDocument.builder()
        .uploadId("repo-test-keep-G")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPG")
        .scanStatus(ScanStatus.PENDING)
        .build();
    repository.saveAll(List.of(docE, docF, docG));

    // Act — delete REPE and REPF together
    repository.deleteAllByNotificationReferenceNumberIn(
        List.of("DRAFT.IMP.2026.REPE", "DRAFT.IMP.2026.REPF"));

    // Assert — only REPG document remains
    List<AccompanyingDocument> remaining = repository.findAll();
    assertThat(remaining).hasSize(1);
    assertThat(remaining.get(0).getUploadId()).isEqualTo("repo-test-keep-G");
  }

  /**
   * deleteAllByNotificationReferenceNumberIn with an empty list deletes nothing.
   */
  @Test
  void deleteAllByNotificationReferenceNumberIn_shouldDeleteNothing_whenListIsEmpty() {
    // Arrange
    AccompanyingDocument doc = AccompanyingDocument.builder()
        .uploadId("repo-test-preserve-001")
        .notificationReferenceNumber("DRAFT.IMP.2026.REPH")
        .scanStatus(ScanStatus.PENDING)
        .build();
    repository.save(doc);

    // Act
    repository.deleteAllByNotificationReferenceNumberIn(List.of());

    // Assert — document untouched
    assertThat(repository.findAll()).hasSize(1);
  }

  // ---------------------------------------------------------------------------
  // Unique index on uploadId
  // ---------------------------------------------------------------------------

  /**
   * Saving two documents with the same {@code uploadId} must fail with
   * {@link DuplicateKeyException}, proving the {@code @Indexed(unique = true)} declaration on
   * {@link AccompanyingDocument#uploadId} is materialised in MongoDB. This guards against
   * accidental removal of the annotation: without the unique index, two records for the same
   * cdp-uploader upload could coexist and corrupt downstream lookups via
   * {@link AccompanyingDocumentRepository#findByUploadId(String)}.
   *
   * <p>The two documents use different {@code notificationReferenceNumber}s and {@code COMPLETE}
   * scan status to avoid colliding with the partial unique compound index on
   * {@code (notificationReferenceNumber, scanStatus=PENDING)} — that way the only constraint
   * either record can violate is the {@code uploadId} unique index.
   */
  @Test
  void save_shouldThrowDuplicateKeyException_whenUploadIdAlreadyExists() {
    // Arrange — first save succeeds
    AccompanyingDocument first = AccompanyingDocument.builder()
        .uploadId("repo-test-duplicate-upload")
        .notificationReferenceNumber("DRAFT.IMP.2026.UNIQ001")
        .scanStatus(ScanStatus.COMPLETE)
        .build();
    repository.save(first);

    AccompanyingDocument duplicate = AccompanyingDocument.builder()
        .uploadId("repo-test-duplicate-upload")
        .notificationReferenceNumber("DRAFT.IMP.2026.UNIQ002")
        .scanStatus(ScanStatus.COMPLETE)
        .build();

    // Act + Assert — second save with the same uploadId must violate the unique index
    assertThatThrownBy(() -> repository.save(duplicate))
        .isInstanceOf(DuplicateKeyException.class);

    // And the original document must still be the only one stored for that uploadId
    assertThat(repository.findByUploadId("repo-test-duplicate-upload"))
        .isPresent()
        .get()
        .satisfies(stored -> assertThat(stored.getNotificationReferenceNumber())
            .isEqualTo("DRAFT.IMP.2026.UNIQ001"));
  }
}
