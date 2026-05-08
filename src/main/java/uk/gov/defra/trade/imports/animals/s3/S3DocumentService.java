package uk.gov.defra.trade.imports.animals.s3;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;

/**
 * Service responsible for streaming documents from S3 to a caller-supplied output stream.
 *
 * <p>Lets AWS SDK exceptions ({@code S3Exception} and friends, all {@code RuntimeException}s)
 * bubble up to {@code GlobalExceptionHandler}; the only thing we wrap here is the checked
 * {@link IOException} from the underlying transfer, which becomes an {@link UncheckedIOException}.
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
   */
  public void streamToOutput(String s3Key, OutputStream outputStream) {
    Objects.requireNonNull(s3Key, "s3Key must not be null");
    String bucket = cdpConfig.s3().documentsBucket();

    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(bucket)
        .key(s3Key)
        .build();

    try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(request)) {
      s3Object.transferTo(outputStream);
    } catch (IOException ex) {
      throw new UncheckedIOException("I/O error while streaming document from S3", ex);
    }
  }
}
