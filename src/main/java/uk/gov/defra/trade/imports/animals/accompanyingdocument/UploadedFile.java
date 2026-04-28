package uk.gov.defra.trade.imports.animals.accompanyingdocument;

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
    String filename,
    String contentType,
    Long contentLength,
    String s3Key,
    String s3Bucket,
    FileStatus fileStatus,
    String checksumSha256,
    String detectedContentType,
    Boolean hasError,
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
