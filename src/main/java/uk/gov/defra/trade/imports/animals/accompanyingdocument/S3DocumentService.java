package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import java.io.IOException;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.exceptions.TradeImportsAnimalsBackendException;

/**
 * Service responsible for streaming documents from S3 to a caller-supplied output stream.
 *
 * <p>Wraps the AWS SDK {@link S3Client} and maps SDK exceptions to
 * {@link TradeImportsAnimalsBackendException} so callers remain decoupled from the AWS SDK.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class S3DocumentService {

  private final S3Client s3Client;
  private final CdpConfig cdpConfig;

  /**
   * Streams the S3 object identified by {@code s3Key} from the configured documents bucket to
   * {@code outputStream}.
   *
   * @param s3Key        the S3 object key
   * @param outputStream the destination output stream; the caller is responsible for closing it
   * @throws TradeImportsAnimalsBackendException if the S3 request fails or an I/O error occurs
   */
  public void streamToOutput(String s3Key, OutputStream outputStream) {
    String bucket = cdpConfig.s3().documentsBucket();

    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(bucket)
        .key(s3Key)
        .build();

    try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(request)) {
      s3Object.transferTo(outputStream);
    } catch (S3Exception ex) {
      log.error(
          "S3 error streaming key={} bucket={}: statusCode={} errorCode={} message={}",
          s3Key,
          bucket,
          ex.statusCode(),
          ex.awsErrorDetails().errorCode(),
          ex.awsErrorDetails().errorMessage());
      throw new TradeImportsAnimalsBackendException(
          "Failed to stream document from S3: " + ex.awsErrorDetails().errorMessage());
    } catch (IOException ex) {
      throw new TradeImportsAnimalsBackendException(
          "I/O error while streaming document from S3: " + ex.getMessage(), ex);
    }
  }
}
