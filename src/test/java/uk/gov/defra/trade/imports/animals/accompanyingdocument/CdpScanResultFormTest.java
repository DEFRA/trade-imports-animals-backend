package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CdpScanResultFormTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
  }

  // ─── Jackson @JsonAnySetter deserialization ──────────────────────────────────

  @Test
  void deserialize_shouldPopulateFilesMap_whenJsonContainsFileField()
      throws JsonProcessingException {
    String json = """
        {
          "certificateFile": {
            "fileId": "file-uuid-1",
            "filename": "cert.pdf",
            "contentType": "application/pdf",
            "fileStatus": "complete",
            "contentLength": 2048,
            "checksumSha256": "abc123",
            "detectedContentType": "application/pdf",
            "s3Key": "upload-uuid/file-uuid-1",
            "s3Bucket": "my-bucket",
            "hasError": false,
            "errorMessage": null
          }
        }
        """;

    CdpScanResultForm form = objectMapper.readValue(json, CdpScanResultForm.class);

    assertThat(form.getFiles()).containsKey("certificateFile");
    CdpScanResultFile file = form.getFiles().get("certificateFile");
    assertThat(file.fileId()).isEqualTo("file-uuid-1");
    assertThat(file.filename()).isEqualTo("cert.pdf");
    assertThat(file.contentType()).isEqualTo("application/pdf");
    assertThat(file.fileStatus()).isEqualTo(FileStatus.COMPLETE);
    assertThat(file.contentLength()).isEqualTo(2048L);
    assertThat(file.checksumSha256()).isEqualTo("abc123");
    assertThat(file.detectedContentType()).isEqualTo("application/pdf");
    assertThat(file.s3Key()).isEqualTo("upload-uuid/file-uuid-1");
    assertThat(file.s3Bucket()).isEqualTo("my-bucket");
    assertThat(file.hasError()).isFalse();
    assertThat(file.errorMessage()).isNull();
  }

  @Test
  void deserialize_shouldPopulateMultipleFileFields_whenJsonContainsMultipleKeys()
      throws JsonProcessingException {
    String json = """
        {
          "passportFile": {
            "fileId": "file-uuid-2",
            "filename": "passport.jpg",
            "contentType": "image/jpeg",
            "fileStatus": "complete",
            "contentLength": 512,
            "checksumSha256": "def456",
            "detectedContentType": "image/jpeg",
            "s3Key": "upload/file-uuid-2",
            "s3Bucket": "bucket",
            "hasError": false,
            "errorMessage": null
          },
          "healthCertificate": {
            "fileId": "file-uuid-3",
            "filename": "health.pdf",
            "contentType": "application/pdf",
            "fileStatus": "rejected",
            "contentLength": 1024,
            "checksumSha256": "ghi789",
            "detectedContentType": "application/pdf",
            "s3Key": "upload/file-uuid-3",
            "s3Bucket": "bucket",
            "hasError": true,
            "errorMessage": "Virus detected"
          }
        }
        """;

    CdpScanResultForm form = objectMapper.readValue(json, CdpScanResultForm.class);

    assertThat(form.getFiles()).hasSize(2);
    assertThat(form.getFiles().get("passportFile").filename()).isEqualTo("passport.jpg");
    assertThat(form.getFiles().get("healthCertificate").fileStatus()).isEqualTo(FileStatus.REJECTED);
    assertThat(form.getFiles().get("healthCertificate").hasError()).isTrue();
    assertThat(form.getFiles().get("healthCertificate").errorMessage()).isEqualTo("Virus detected");
  }

  @Test
  void deserialize_shouldReturnEmptyFilesMap_whenJsonObjectIsEmpty()
      throws JsonProcessingException {
    CdpScanResultForm form = objectMapper.readValue("{}", CdpScanResultForm.class);

    assertThat(form.getFiles()).isNotNull();
    assertThat(form.getFiles()).isEmpty();
  }

  // ─── addFile() mutability ────────────────────────────────────────────────────

  @Test
  void addFile_shouldAddEntryToFilesMap() {
    CdpScanResultForm form = new CdpScanResultForm();
    CdpScanResultFile file = new CdpScanResultFile(
        "file-id", "doc.pdf", "application/pdf", FileStatus.COMPLETE,
        1024L, "sha256", "application/pdf", "s3key", "bucket", false, null);

    form.addFile("docFile", file);

    assertThat(form.getFiles()).containsEntry("docFile", file);
  }

  // ─── Explicit constructor — defensive copy ────────────────────────────────────

  @Test
  void constructor_shouldDefensivelyCopy_whenPassedUnmodifiableMap() {
    CdpScanResultFile file = new CdpScanResultFile(
        "file-id", "doc.pdf", "application/pdf", FileStatus.COMPLETE,
        1024L, "sha256", "application/pdf", "s3key", "bucket", false, null);
    Map<String, CdpScanResultFile> unmodifiable = Collections.unmodifiableMap(Map.of("doc", file));

    CdpScanResultForm form = new CdpScanResultForm(unmodifiable);

    // addFile must not throw UnsupportedOperationException even though the source map was
    // unmodifiable — the constructor must have taken a defensive copy.
    assertThatCode(() -> form.addFile("extraFile", file)).doesNotThrowAnyException();
    assertThat(form.getFiles()).containsKey("extraFile");
    assertThat(form.getFiles()).containsKey("doc");
  }

  @Test
  void constructor_shouldNotMutateOriginalMap_whenAddFileIsCalled() {
    CdpScanResultFile file = new CdpScanResultFile(
        "file-id", "doc.pdf", "application/pdf", FileStatus.COMPLETE,
        1024L, "sha256", "application/pdf", "s3key", "bucket", false, null);
    Map<String, CdpScanResultFile> original = new java.util.LinkedHashMap<>(Map.of("doc", file));

    CdpScanResultForm form = new CdpScanResultForm(original);
    form.addFile("extraFile", file);

    // The defensive copy means mutations to form.files do not affect the original map.
    assertThat(original).doesNotContainKey("extraFile");
  }
}
