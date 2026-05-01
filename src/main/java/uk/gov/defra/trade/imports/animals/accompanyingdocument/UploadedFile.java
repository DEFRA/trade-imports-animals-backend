package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * Immutable value object representing a single file within an {@link AccompanyingDocument}.
 *
 * <p>Stored as an embedded sub-document in the {@code accompanying_documents} collection —
 * intentionally no {@code @Document} annotation. All fields are mapped directly from the
 * cdp-uploader callback payload.
 *
 * <p>{@code s3Key} follows the pattern {@code "{uploadId}/{fileId}"} for complete files and is
 * {@code null} for rejected files (the file was not persisted to S3).
 * The {@code fileId} component of the key is an internal CDP uploader identifier and is not
 * exposed in the API — callers download via upload ID only.
 */
@Builder
public record UploadedFile(
    @Schema(description = "Original filename as provided by the uploader")
    String filename,

    @Schema(description = "MIME content type declared by the uploader")
    String contentType,

    @Schema(description = "File size in bytes")
    Long contentLength,

    @Schema(description = "S3 object key in the format \"{uploadId}/{fileId}\"; null for rejected files")
    String s3Key,

    @Schema(description = "S3 bucket in which the file is stored")
    String s3Bucket,

    @Schema(description = "Current status of the file within the upload lifecycle")
    FileStatus fileStatus,

    @Schema(description = "SHA-256 checksum of the uploaded file content")
    String checksumSha256,

    @Schema(description = "Content type detected by the virus/content scanner, may differ from contentType")
    String detectedContentType,

    @Schema(description = "Whether the file was rejected due to a scan or validation error")
    Boolean hasError,

    @Schema(description = "Human-readable error message if hasError is true; null otherwise")
    String errorMessage) {

  /**
   * Creates an {@link UploadedFile} from a {@link CdpScanResultFile} callback entry.
   *
   * @param f the cdp-uploader scan result file entry
   * @return an {@code UploadedFile} populated from {@code f}
   */
  public static UploadedFile from(CdpScanResultFile f) {
    return UploadedFile.builder()
        .filename(f.filename())
        .contentType(f.contentType())
        .contentLength(f.contentLength())
        .s3Key(f.s3Key())
        .s3Bucket(f.s3Bucket())
        .fileStatus(f.fileStatus())
        .checksumSha256(f.checksumSha256())
        .detectedContentType(f.detectedContentType())
        .hasError(f.hasError())
        .errorMessage(f.errorMessage())
        .build();
  }
}
