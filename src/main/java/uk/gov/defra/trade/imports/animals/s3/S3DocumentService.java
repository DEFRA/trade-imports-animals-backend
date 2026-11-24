package uk.gov.defra.trade.imports.animals.s3;

import io.micrometer.core.annotation.Timed;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCall;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCallRecorder;

/**
 * Streams documents from S3 to a caller-supplied output stream.
 *
 * <p>Lets AWS SDK exceptions ({@code S3Exception} and friends, all {@code RuntimeException}s)
 * bubble up to {@code GlobalExceptionHandler}; the only thing we wrap here is the checked
 * {@link IOException} from the underlying transfer, which becomes an {@link UncheckedIOException}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class S3DocumentService {

  private static final ExternalCall S3_GET_OBJECT = new ExternalCall(Dependency.S3, "get-object");
  private static final int BUFFER_SIZE = 8192;

  private final S3Client s3Client;
  private final CdpConfig cdpConfig;
  private final ExternalCallRecorder externalCallRecorder;

  /**
   * Streams the S3 object identified by {@code s3Key} from the configured documents bucket to
   * {@code outputStream}, recording the fetch and the read of the whole body as one {@code
   * s3/get-object} call.
   *
   * @param s3Key        the S3 object key
   * @param outputStream the destination output stream; the caller is responsible for closing it
   */
  @Timed("service.streamToOutput.time")
  public void streamToOutput(String s3Key, OutputStream outputStream) {
    Objects.requireNonNull(s3Key, "s3Key must not be null");
    String bucket = cdpConfig.s3().documentsBucket();

    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(bucket)
        .key(s3Key)
        .build();

    AtomicReference<IOException> outputFailure = new AtomicReference<>();
    externalCallRecorder.measure(
        S3_GET_OBJECT, () -> outputFailure.set(readObjectInto(request, outputStream)));
    IOException failure = outputFailure.get();
    if (failure != null) {
      throw new UncheckedIOException("I/O error while streaming document from S3", failure);
    }
  }

  private IOException readObjectInto(GetObjectRequest request, OutputStream outputStream) {
    try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(request)) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int read;
      while ((read = s3Object.read(buffer)) != -1) {
        IOException writeFailure = writeChunk(outputStream, buffer, read);
        if (writeFailure != null) {
          return writeFailure;
        }
      }
      return null;
    } catch (IOException ex) {
      throw new UncheckedIOException("I/O error while streaming document from S3", ex);
    }
  }

  private static IOException writeChunk(OutputStream outputStream, byte[] buffer, int length) {
    try {
      outputStream.write(buffer, 0, length);
      return null;
    } catch (IOException ex) {
      return ex;
    }
  }
}
