package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.util.List;
import java.util.Map;

/**
 * Request body sent to the cdp-uploader {@code /initiate} endpoint.
 *
 * <p>Field names must exactly match cdp-uploader README parameters.
 */
public record CdpUploaderInitiateRequest(
    String redirect,
    String callback,
    String s3Bucket,
    String s3Path,
    Long maxFileSize,
    List<String> mimeTypes,
    Map<String, String> metadata) {}
