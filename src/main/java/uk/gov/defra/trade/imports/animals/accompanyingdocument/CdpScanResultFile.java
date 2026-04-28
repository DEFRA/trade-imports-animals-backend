package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import jakarta.annotation.Nullable;

/**
 * Represents a single file entry within a cdp-uploader scan result callback payload.
 *
 * <p>Field names intentionally use camelCase to align with the cdp-uploader JSON contract;
 * no {@code @JsonProperty} annotations are required.
 *
 * @param fileId              identifier assigned by cdp-uploader; retained for contract
 *                            completeness but not persisted
 * @param filename            original filename as supplied by the uploader
 * @param contentType         MIME type declared by the client at upload time
 * @param fileStatus          scan outcome status (e.g. {@code complete} or {@code rejected})
 * @param contentLength       size of the file in bytes
 * @param checksumSha256      SHA-256 hex digest of the uploaded file content
 * @param detectedContentType MIME type detected by the server-side content inspection;
 *                            may differ from {@code contentType}
 * @param s3Key               S3 object key where the file is stored; {@code null} when the
 *                            file was rejected or an error occurred
 * @param s3Bucket            S3 bucket name where the file is stored; {@code null} when the
 *                            file was rejected or an error occurred
 * @param hasError            {@code true} if the scan reported an error for this file
 * @param errorMessage        human-readable error description; {@code null} when there is no error
 */
public record CdpScanResultFile(
    String fileId,
    String filename,
    String contentType,
    FileStatus fileStatus,
    Long contentLength,
    String checksumSha256,
    String detectedContentType,
    @Nullable String s3Key,
    @Nullable String s3Bucket,
    Boolean hasError,
    @Nullable String errorMessage) {}
