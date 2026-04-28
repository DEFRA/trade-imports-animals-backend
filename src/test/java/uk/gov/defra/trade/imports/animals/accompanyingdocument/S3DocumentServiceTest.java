package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import uk.gov.defra.trade.imports.animals.exceptions.TradeImportsAnimalsBackendException;

@ExtendWith(MockitoExtension.class)
class S3DocumentServiceTest {

  @Mock
  private S3Client s3Client;

  @Mock
  private CdpConfig cdpConfig;

  @Mock
  private CdpConfig.S3Config s3Config;

  private S3DocumentService s3DocumentService;

  @BeforeEach
  void setUp() {
    s3DocumentService = new S3DocumentService(s3Client, cdpConfig);
  }

  @Test
  void streamToOutput_shouldCallS3ClientWithCorrectBucketAndKey() throws IOException {
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
  void streamToOutput_shouldThrowWrappedException_whenS3ExceptionThrown() {
    // Given
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

    // When / Then
    assertThatThrownBy(() -> s3DocumentService.streamToOutput(s3Key, outputStream))
        .isInstanceOf(TradeImportsAnimalsBackendException.class)
        .hasMessageContaining("Failed to stream document from S3");
  }

  @Test
  void streamToOutput_shouldThrowWrappedException_whenIOExceptionThrown() throws IOException {
    // Given
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

    // When / Then
    assertThatThrownBy(() -> s3DocumentService.streamToOutput(s3Key, outputStream))
        .isInstanceOf(TradeImportsAnimalsBackendException.class)
        .hasMessageContaining("I/O error while streaming document from S3")
        .hasCauseInstanceOf(IOException.class);
  }
}
