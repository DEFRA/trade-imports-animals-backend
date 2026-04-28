package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccompanyingDocumentDtoTest {

  // ─── from() — full field mapping ────────────────────────────────────────────

  @Test
  void from_shouldMapAllScalarFieldsFromEntity() {
    Instant dateOfIssue = Instant.parse("2026-01-15T00:00:00Z");
    Instant created = Instant.parse("2026-02-01T10:00:00Z");
    Instant updated = Instant.parse("2026-02-02T12:00:00Z");

    AccompanyingDocument entity = AccompanyingDocument.builder()
        .id("doc-id-1")
        .notificationReferenceNumber("DRAFT.IMP.2026.abc123")
        .uploadId("upload-uuid-1")
        .documentType(DocumentType.ITAHC)
        .documentReference("ITAHC/2026/001")
        .dateOfIssue(dateOfIssue)
        .scanStatus(ScanStatus.COMPLETE)
        .created(created)
        .updated(updated)
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.id()).isEqualTo("doc-id-1");
    assertThat(dto.notificationReferenceNumber()).isEqualTo("DRAFT.IMP.2026.abc123");
    assertThat(dto.uploadId()).isEqualTo("upload-uuid-1");
    assertThat(dto.documentType()).isEqualTo(DocumentType.ITAHC);
    assertThat(dto.documentReference()).isEqualTo("ITAHC/2026/001");
    assertThat(dto.dateOfIssue()).isEqualTo(dateOfIssue);
    assertThat(dto.scanStatus()).isEqualTo(ScanStatus.COMPLETE);
    assertThat(dto.created()).isEqualTo(LocalDateTime.ofInstant(created, ZoneOffset.UTC));
    assertThat(dto.updated()).isEqualTo(LocalDateTime.ofInstant(updated, ZoneOffset.UTC));
  }

  // ─── from() — Instant → LocalDateTime conversion ────────────────────────────

  @Test
  void from_shouldConvertCreatedInstantToUtcLocalDateTime() {
    Instant created = Instant.parse("2026-06-15T13:30:00Z");

    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-2")
        .created(created)
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.created()).isEqualTo(LocalDateTime.of(2026, 6, 15, 13, 30, 0));
  }

  @Test
  void from_shouldConvertUpdatedInstantToUtcLocalDateTime() {
    Instant updated = Instant.parse("2026-07-20T08:00:00Z");

    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-3")
        .updated(updated)
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.updated()).isEqualTo(LocalDateTime.of(2026, 7, 20, 8, 0, 0));
  }

  @Test
  void from_shouldMapCreatedToNull_whenEntityCreatedIsNull() {
    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-null-created")
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.created()).isNull();
  }

  @Test
  void from_shouldMapUpdatedToNull_whenEntityUpdatedIsNull() {
    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-null-updated")
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.updated()).isNull();
  }

  // ─── from() — files list mapping ────────────────────────────────────────────

  @Test
  void from_shouldMapFilesToUploadedFileDtos() {
    UploadedFile file = UploadedFile.builder()
        .filename("cert.pdf")
        .contentType("application/pdf")
        .contentLength(2048L)
        .s3Key("upload-uuid-4/internal-file-id")
        .s3Bucket("my-bucket")
        .fileStatus(FileStatus.COMPLETE)
        .checksumSha256("abc123sha")
        .detectedContentType("application/pdf")
        .hasError(false)
        .errorMessage(null)
        .build();

    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-4")
        .files(List.of(file))
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.files()).hasSize(1);
    UploadedFileDto fileDto = dto.files().get(0);
    assertThat(fileDto.filename()).isEqualTo("cert.pdf");
    assertThat(fileDto.contentType()).isEqualTo("application/pdf");
    assertThat(fileDto.contentLength()).isEqualTo(2048L);
    assertThat(fileDto.fileStatus()).isEqualTo(FileStatus.COMPLETE);
    assertThat(fileDto.checksumSha256()).isEqualTo("abc123sha");
    assertThat(fileDto.detectedContentType()).isEqualTo("application/pdf");
    assertThat(fileDto.hasError()).isFalse();
    assertThat(fileDto.errorMessage()).isNull();
  }

  @Test
  void from_shouldMapFilesToEmptyList_whenEntityFilesIsEmpty() {
    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-5")
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.files()).isNotNull();
    assertThat(dto.files()).isEmpty();
  }

  @Test
  void from_shouldMapMultipleFiles() {
    UploadedFile file1 = UploadedFile.builder()
        .filename("passport.jpg")
        .contentType("image/jpeg")
        .contentLength(512L)
        .fileStatus(FileStatus.COMPLETE)
        .build();
    UploadedFile file2 = UploadedFile.builder()
        .filename("cert.pdf")
        .contentType("application/pdf")
        .contentLength(1024L)
        .fileStatus(FileStatus.REJECTED)
        .build();

    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-6")
        .files(List.of(file1, file2))
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    assertThat(dto.files()).hasSize(2);
    assertThat(dto.files().get(0).filename()).isEqualTo("passport.jpg");
    assertThat(dto.files().get(1).filename()).isEqualTo("cert.pdf");
  }

  @Test
  void from_filesDto_shouldNotExposeS3KeyOrS3Bucket() {
    UploadedFile file = UploadedFile.builder()
        .filename("doc.pdf")
        .s3Key("upload-uuid-7/internal-file-id")
        .s3Bucket("sensitive-bucket")
        .fileStatus(FileStatus.COMPLETE)
        .build();

    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-7")
        .files(List.of(file))
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    // UploadedFileDto record has no s3Key or s3Bucket accessors — compile-time guarantee.
    // This test documents that the DTO type does not expose those fields.
    UploadedFileDto fileDto = dto.files().get(0);
    assertThat(fileDto).isNotNull();
    assertThat(fileDto.filename()).isEqualTo("doc.pdf");
  }

  // ─── from() — null guard ─────────────────────────────────────────────────────

  @Test
  void from_shouldThrowNullPointerException_whenEntityIsNull() {
    assertThatThrownBy(() -> AccompanyingDocumentDto.from(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("entity must not be null");
  }

  // ─── from() — uploadUrl not exposed ─────────────────────────────────────────

  @Test
  void from_shouldNotExposeUploadUrl() {
    AccompanyingDocument entity = AccompanyingDocument.builder()
        .uploadId("upload-uuid-8")
        .uploadUrl("https://cdp-uploader.internal/form/upload-uuid-8")
        .build();

    AccompanyingDocumentDto dto = AccompanyingDocumentDto.from(entity);

    // AccompanyingDocumentDto record has no uploadUrl accessor — compile-time guarantee.
    // This test documents that the DTO type does not expose the uploadUrl field.
    assertThat(dto.uploadId()).isEqualTo("upload-uuid-8");
  }
}
