package uk.gov.defra.trade.imports.animals.accompanyingdocument;

/**
 * Represents a single file entry within a cdp-uploader scan result callback payload.
 */
public record CdpScanResultFile(
    String fileId,
    String filename,
    String contentType,
    FileStatus fileStatus,
    Long contentLength,
    String checksumSha256,
    String detectedContentType,
    String s3Key,
    String s3Bucket,
    Boolean hasError,
    String errorMessage) {}
