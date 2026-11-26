package uk.gov.defra.trade.imports.animals.s3;

import io.micrometer.core.annotation.Timed;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCall;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCallRecorder;

@Service
@Slf4j
@RequiredArgsConstructor
public class S3DocumentService {

  private static final ExternalCall S3_GET_OBJECT = new ExternalCall(Dependency.S3, "get-object");

  private final S3Client s3Client;
  private final CdpConfig cdpConfig;
  private final ExternalCallRecorder externalCallRecorder;

  /**
   * Streams the S3 object identified by {@code s3Key} from the configured documents bucket to
   * {@code outputStream}, recording one {@code s3/get-object} call whose duration is the time spent
   * waiting on S3, not the time spent writing to {@code outputStream}.
   *
   * @param s3Key        the S3 object key
   * @param outputStream the destination output stream; the caller is responsible for closing it
   * @throws UncheckedIOException when reading from S3 fails, or when writing to {@code
   *     outputStream} fails, each with its own message naming {@code s3Key}
   */
  @Timed("service.streamToOutput.time")
  public void streamToOutput(String s3Key, OutputStream outputStream) {
    Objects.requireNonNull(s3Key, "s3Key must not be null");
    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(cdpConfig.s3().documentsBucket())
        .key(s3Key)
        .build();

    Duration s3Time = Duration.ZERO;
    long waitStartedAt = externalCallRecorder.callStartedAt();
    try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(request)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = s3Object.read(buffer)) != -1) {
        s3Time = s3TimeWithWaitSince(s3Time, waitStartedAt);
        writeToClient(s3Key, outputStream, buffer, read);
        waitStartedAt = externalCallRecorder.callStartedAt();
      }
    } catch (ClientStoppedReadingException e) {
      externalCallRecorder.recordSuccess(S3_GET_OBJECT, s3Time);
      throw e;
    } catch (SdkException e) {
      externalCallRecorder.recordFailure(S3_GET_OBJECT, s3TimeWithWaitSince(s3Time, waitStartedAt), e);
      throw e;
    } catch (IOException e) {
      externalCallRecorder.recordFailure(S3_GET_OBJECT, s3TimeWithWaitSince(s3Time, waitStartedAt), e);
      throw new UncheckedIOException("Could not read document " + s3Key + " from S3", e);
    }
    externalCallRecorder.recordSuccess(S3_GET_OBJECT, s3TimeWithWaitSince(s3Time, waitStartedAt));
  }

  private Duration s3TimeWithWaitSince(Duration s3Time, long waitStartedAt) {
    return s3Time.plus(externalCallRecorder.elapsedSince(waitStartedAt));
  }

  private static void writeToClient(
      String s3Key, OutputStream outputStream, byte[] buffer, int length) {
    try {
      outputStream.write(buffer, 0, length);
    } catch (IOException clientFailure) {
      log.info("Client stopped reading document {} from S3", s3Key, clientFailure);
      throw new ClientStoppedReadingException(s3Key, clientFailure);
    }
  }

  private static final class ClientStoppedReadingException extends UncheckedIOException {
    ClientStoppedReadingException(String s3Key, IOException cause) {
      super("Client stopped reading document " + s3Key, cause);
    }
  }
}
