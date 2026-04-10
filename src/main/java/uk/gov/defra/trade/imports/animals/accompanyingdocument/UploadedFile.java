package uk.gov.defra.trade.imports.animals.accompanyingdocument;

/**
 * Immutable value object representing a single file within an {@link AccompanyingDocument}.
 *
 * <p>Stored as an embedded sub-document in the {@code accompanying_documents} collection —
 * intentionally no {@code @Document} annotation. All fields are mapped directly from the
 * cdp-uploader callback payload.
 *
 * <p>{@code s3Key} follows the pattern {@code "{uploadId}/{fileId}"} for complete files and is
 * {@code null} for rejected files (the file was not persisted to S3).
 */
public record UploadedFile(
    String fileId,
    String filename,
    String contentType,
    Long contentLength,
    String s3Key,
    String s3Bucket,
    FileStatus fileStatus,
    String checksumSha256,
    String detectedContentType,
    Boolean hasError,
    String errorMessage) {}
