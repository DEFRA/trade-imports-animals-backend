package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * Read-only projection of an {@link UploadedFile} for API responses.
 *
 * <p>Deliberately omits {@code s3Key} and {@code s3Bucket} — these are internal S3 storage
 * coordinates that no API consumer requires and must not be exposed in responses.
 *
 * <p>Use {@link #from(UploadedFile)} to create an instance from the domain object.
 */
public record UploadedFileDto(
    @Schema(description = "Original filename as provided by the uploader")
    String filename,

    @Schema(description = "MIME content type declared by the uploader")
    String contentType,

    @Schema(description = "File size in bytes")
    Long contentLength,

    @Schema(description = "Current status of the file within the upload lifecycle")
    FileStatus fileStatus,

    @Schema(description = "SHA-256 checksum of the uploaded file content")
    String checksumSha256,

    @Schema(description = "Content type detected by the virus/content scanner, may differ from contentType")
    String detectedContentType,

    @Schema(description = "Whether the file was rejected due to a scan or validation error; "
        + "always non-null (defaults to false when unknown)")
    Boolean hasError,

    @Schema(description = "Human-readable error message if hasError is true; null otherwise")
    String errorMessage) {

  /**
   * Compact constructor that normalises {@code hasError} so API consumers see a non-null value.
   *
   * <p>A {@code null} {@code hasError} is treated as {@code false} — i.e. no error. This keeps the
   * API contract simple: {@code hasError} is always either {@code true} or {@code false}.
   */
  public UploadedFileDto {
    hasError = hasError != null && hasError;
  }

  /**
   * Maps an {@link UploadedFile} to a DTO, omitting {@code s3Key} and {@code s3Bucket}.
   *
   * @param file the domain object to map; must not be {@code null}
   * @return a new {@code UploadedFileDto} populated from {@code file}
   */
  public static UploadedFileDto from(UploadedFile file) {
    Objects.requireNonNull(file, "file must not be null");
    return new UploadedFileDto(
        file.filename(),
        file.contentType(),
        file.contentLength(),
        file.fileStatus(),
        file.checksumSha256(),
        file.detectedContentType(),
        file.hasError(),
        file.errorMessage());
  }
}
