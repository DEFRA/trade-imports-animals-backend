package uk.gov.defra.trade.imports.animals.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.assertOneLine;
import static uk.gov.defra.trade.imports.animals.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.S3_GET_OBJECT;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationSampleCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.totalDurationMillis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCallRecorder;
import uk.gov.defra.trade.imports.animals.metrics.LastCallOutcomes;

@ExtendWith(MockitoExtension.class)
class S3DocumentServiceTest {

  private static final String BUCKET = "trade-imports-animals-documents";
  private static final byte[] CONTENT = "test content".getBytes(StandardCharsets.UTF_8);

  @Mock
  private S3Client s3Client;

  @Mock
  private CdpConfig cdpConfig;

  @Mock
  private CdpConfig.S3Config s3Config;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AtomicLong nanoTime = new AtomicLong();

  private S3DocumentService s3DocumentService;

  @BeforeEach
  void setUp() {
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn(BUCKET);
    s3DocumentService =
        new S3DocumentService(
            s3Client,
            cdpConfig,
            new ExternalCallRecorder(meterRegistry, new LastCallOutcomes(), nanoTime::get));
  }

  @Test
  void streamToOutput_shouldCallS3ClientWithCorrectBucketAndKey() {
    // Given
    String s3Key = "upload-id-001/file-id-001";
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    s3Returns(new ByteArrayInputStream(CONTENT));

    // When
    s3DocumentService.streamToOutput(s3Key, outputStream);

    // Then
    verify(s3Client).getObject(GetObjectRequest.builder()
        .bucket(BUCKET)
        .key(s3Key)
        .build());
    assertThat(outputStream.toByteArray()).isEqualTo(CONTENT);
  }

  @Test
  void streamToOutput_shouldLetS3ExceptionBubble_whenS3RejectsRequest() {
    // S3Exception is a RuntimeException — we don't catch it; GlobalExceptionHandler does.
    String s3Key = "upload-id-002/file-id-002";
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    AwsErrorDetails errorDetails = AwsErrorDetails.builder()
        .errorCode("NoSuchKey")
        .errorMessage("The specified key does not exist.")
        .build();
    S3Exception s3Exception = (S3Exception) S3Exception.builder()
        .statusCode(404)
        .awsErrorDetails(errorDetails)
        .build();

    when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Exception);

    assertThatThrownBy(() -> s3DocumentService.streamToOutput(s3Key, outputStream))
        .isSameAs(s3Exception);
  }

  @Test
  void streamToOutput_shouldRecordAFailedGetObjectAndThrowNamingTheKey_whenReadingTheBodyFails()
      throws IOException {
    s3Returns(aBodyThatFailsOnRead());
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    assertThatThrownBy(
            () -> s3DocumentService.streamToOutput("upload-id-003/file-id-003", outputStream))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Could not read document upload-id-003/file-id-003 from S3")
        .hasCauseInstanceOf(IOException.class);

    assertThat(failureTotal(meterRegistry, S3_GET_OBJECT)).isEqualTo(1);
    assertThat(durationSampleCount(meterRegistry, S3_GET_OBJECT)).isEqualTo(1);
  }

  @Test
  void streamToOutput_shouldRecordOnlyTheTimeSpentWaitingOnS3_notWritingToTheClient() {
    s3Returns(aBodyTakingMillisPerRead(5));
    OutputStream slowClient =
        new ByteArrayOutputStream() {
          @Override
          public void write(byte[] bytes, int offset, int length) {
            nanoTime.addAndGet(Duration.ofMillis(100).toNanos());
            super.write(bytes, offset, length);
          }
        };

    s3DocumentService.streamToOutput("some-key", slowClient);

    assertThat(totalDurationMillis(meterRegistry, S3_GET_OBJECT)).isEqualTo(10);
    assertThat(failureTotal(meterRegistry, S3_GET_OBJECT)).isZero();
  }

  @Test
  void streamToOutput_shouldRecordASuccessLogTheHangUpAndThrowNamingTheKey_whenTheClientHangsUp() {
    s3Returns(new ByteArrayInputStream(CONTENT));
    IOException brokenPipe = new IOException("Broken pipe");
    OutputStream hungUp =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw brokenPipe;
          }
        };

    List<ILoggingEvent> lines =
        loggedBy(
            S3DocumentService.class,
            () ->
                assertThatThrownBy(() -> s3DocumentService.streamToOutput("some-key", hungUp))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessage("Client stopped reading document some-key")
                    .hasCause(brokenPipe));

    assertThat(failureTotal(meterRegistry, S3_GET_OBJECT)).isZero();
    assertThat(durationSampleCount(meterRegistry, S3_GET_OBJECT)).isEqualTo(1);
    ILoggingEvent line =
        assertOneLine(lines, Level.INFO, "Client stopped reading document some-key from S3");
    assertThat(line.getThrowableProxy().getMessage()).isEqualTo("Broken pipe");
  }

  private void s3Returns(InputStream body) {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(body)));
  }

  private static InputStream aBodyThatFailsOnRead() throws IOException {
    InputStream failingStream = mock(InputStream.class);
    when(failingStream.read(any(byte[].class), anyInt(), anyInt()))
        .thenThrow(new IOException("Connection reset"));
    return failingStream;
  }

  private InputStream aBodyTakingMillisPerRead(long millis) {
    return new FilterInputStream(new ByteArrayInputStream(CONTENT)) {
      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        nanoTime.addAndGet(Duration.ofMillis(millis).toNanos());
        return super.read(bytes, offset, length);
      }
    };
  }
}
