package uk.gov.defra.trade.imports.animals.accompanyingdocument;

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
    String filename,
    String contentType,
    Long contentLength,
    FileStatus fileStatus,
    String checksumSha256,
    String detectedContentType,
    Boolean hasError,
    String errorMessage) {

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
