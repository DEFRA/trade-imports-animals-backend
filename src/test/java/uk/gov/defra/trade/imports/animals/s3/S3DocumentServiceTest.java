package uk.gov.defra.trade.imports.animals.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.durationCount;
import static uk.gov.defra.trade.imports.animals.testsupport.ExternalCallMeters.failureTotal;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import uk.gov.defra.trade.imports.animals.metrics.Dependency;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCall;
import uk.gov.defra.trade.imports.animals.metrics.ExternalCallRecorder;
import uk.gov.defra.trade.imports.animals.metrics.LastCallOutcomes;

@ExtendWith(MockitoExtension.class)
class S3DocumentServiceTest {

  @Mock
  private S3Client s3Client;

  @Mock
  private CdpConfig cdpConfig;

  @Mock
  private CdpConfig.S3Config s3Config;

  private static final ExternalCall GET_OBJECT = new ExternalCall(Dependency.S3, "get-object");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private S3DocumentService s3DocumentService;

  @BeforeEach
  void setUp() {
    s3DocumentService =
        new S3DocumentService(
            s3Client, cdpConfig, new ExternalCallRecorder(meterRegistry, new LastCallOutcomes()));
  }

  @Test
  void streamToOutput_shouldCallS3ClientWithCorrectBucketAndKey() {
    // Given
    String s3Key = "upload-id-001/file-id-001";
    String bucket = "trade-imports-animals-documents";
    byte[] expectedBytes = "test content".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn(bucket);

    ResponseInputStream<GetObjectResponse> responseInputStream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(expectedBytes)));

    when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseInputStream);

    // When
    s3DocumentService.streamToOutput(s3Key, outputStream);

    // Then
    verify(s3Client).getObject(GetObjectRequest.builder()
        .bucket(bucket)
        .key(s3Key)
        .build());
    assertThat(outputStream.toByteArray()).isEqualTo(expectedBytes);
  }

  @Test
  void streamToOutput_shouldLetS3ExceptionBubble_whenS3RejectsRequest() {
    // S3Exception is a RuntimeException — we don't catch it; GlobalExceptionHandler does.
    String s3Key = "upload-id-002/file-id-002";
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn("trade-imports-animals-documents");

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
  void streamToOutput_shouldWrapIOExceptionAsUnchecked_whenTransferFails() throws IOException {
    String s3Key = "upload-id-003/file-id-003";
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn("trade-imports-animals-documents");

    InputStream failingStream = mock(InputStream.class);
    when(failingStream.read(any(byte[].class), anyInt(), anyInt()))
        .thenThrow(new IOException("Simulated I/O failure"));

    ResponseInputStream<GetObjectResponse> responseInputStream =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(failingStream));

    when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseInputStream);

    assertThatThrownBy(() -> s3DocumentService.streamToOutput(s3Key, outputStream))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("I/O error while streaming document from S3")
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void streamToOutput_shouldRecordAFailedGetObject_whenTheBodyFailsPartWay() throws IOException {
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn("trade-imports-animals-documents");
    InputStream failingStream = mock(InputStream.class);
    when(failingStream.read(any(byte[].class), anyInt(), anyInt()))
        .thenThrow(new IOException("Connection reset"));
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(failingStream)));

    assertThatThrownBy(
            () -> s3DocumentService.streamToOutput("some-key", new ByteArrayOutputStream()))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(failureTotal(meterRegistry, GET_OBJECT)).isEqualTo(1);
    assertThat(durationCount(meterRegistry, GET_OBJECT)).isEqualTo(1);
  }

  @Test
  void streamToOutput_shouldRecordASuccessfulGetObject_whenTheClientHangsUpOnTheOutput() {
    when(cdpConfig.s3()).thenReturn(s3Config);
    when(s3Config.documentsBucket()).thenReturn("trade-imports-animals-documents");
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(
                    new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)))));
    OutputStream hungUp =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("Broken pipe");
          }
        };

    assertThatThrownBy(() -> s3DocumentService.streamToOutput("some-key", hungUp))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(failureTotal(meterRegistry, GET_OBJECT)).isZero();
    assertThat(durationCount(meterRegistry, GET_OBJECT)).isEqualTo(1);
  }
}
