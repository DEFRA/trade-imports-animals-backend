package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccompanyingDocumentTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
  }

  // ─── Builder / constructor tests ──────────────────────────────────────────

  @Test
  void builder_shouldConstructDocumentWithAllFields() {
    Instant now = Instant.now();

    AccompanyingDocument doc = AccompanyingDocument.builder()
        .id("doc-id-1")
        .version(1L)
        .notificationReferenceNumber("DRAFT.IMP.2026.abc123")
        .uploadId("upload-uuid-1")
        .documentType(DocumentType.ITAHC)
        .documentReference("ITAHC/2026/001")
        .dateOfIssue(now)
        .scanStatus(ScanStatus.COMPLETE)
        .created(now)
        .updated(now)
        .build();

    assertThat(doc.getId()).isEqualTo("doc-id-1");
    assertThat(doc.getVersion()).isEqualTo(1L);
    assertThat(doc.getNotificationReferenceNumber()).isEqualTo("DRAFT.IMP.2026.abc123");
    assertThat(doc.getUploadId()).isEqualTo("upload-uuid-1");
    assertThat(doc.getDocumentType()).isEqualTo(DocumentType.ITAHC);
    assertThat(doc.getDocumentReference()).isEqualTo("ITAHC/2026/001");
    assertThat(doc.getDateOfIssue()).isEqualTo(now);
    assertThat(doc.getScanStatus()).isEqualTo(ScanStatus.COMPLETE);
    assertThat(doc.getCreated()).isEqualTo(now);
    assertThat(doc.getUpdated()).isEqualTo(now);
  }

  @Test
  void noArgsConstructor_shouldProduceEmptyDocument() {
    AccompanyingDocument doc = new AccompanyingDocument();

    assertThat(doc.getId()).isNull();
    assertThat(doc.getUploadId()).isNull();
  }

  // ─── files default to empty list ─────────────────────────────────────────

  @Test
  void builder_filesDefaultsToEmptyList_whenNotSet() {
    AccompanyingDocument doc = AccompanyingDocument.builder().build();

    assertThat(doc.getFiles()).isNotNull();
    assertThat(doc.getFiles()).isEmpty();
  }

  @Test
  void builder_filesCanBePopulated() {
    UploadedFile file = new UploadedFile(
        "test.pdf", "application/pdf", 1024L,
        "upload-uuid-1/some-internal-file-id", "my-bucket", FileStatus.COMPLETE,
        "sha256hash", "application/pdf", false, null);

    AccompanyingDocument doc = AccompanyingDocument.builder()
        .files(List.of(file))
        .build();

    assertThat(doc.getFiles()).hasSize(1);
    assertThat(doc.getFiles().get(0).filename()).isEqualTo("test.pdf");
  }

  // ─── FileStatus JSON serialisation ───────────────────────────────────────

  @Test
  void fileStatus_complete_serialisesToLowercaseString() throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(FileStatus.COMPLETE);
    assertThat(json).isEqualTo("\"complete\"");
  }

  @Test
  void fileStatus_rejected_serialisesToLowercaseString() throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(FileStatus.REJECTED);
    assertThat(json).isEqualTo("\"rejected\"");
  }

  @Test
  void fileStatus_complete_deserialisesFromLowercaseString() throws JsonProcessingException {
    FileStatus status = objectMapper.readValue("\"complete\"", FileStatus.class);
    assertThat(status).isEqualTo(FileStatus.COMPLETE);
  }

  @Test
  void fileStatus_rejected_deserialisesFromLowercaseString() throws JsonProcessingException {
    FileStatus status = objectMapper.readValue("\"rejected\"", FileStatus.class);
    assertThat(status).isEqualTo(FileStatus.REJECTED);
  }

  // ─── dateOfIssue JSON round-trip ─────────────────────────────────────────

  @Test
  void dateOfIssue_instantRoundTrip_survivesJsonSerialiseDeserialise() throws JsonProcessingException {
    Instant original = Instant.parse("2026-01-15T00:00:00Z");

    AccompanyingDocument doc = AccompanyingDocument.builder()
        .uploadId("upload-round-trip-1")
        .dateOfIssue(original)
        .build();

    String json = objectMapper.writeValueAsString(doc);
    AccompanyingDocument deserialised = objectMapper.readValue(json, AccompanyingDocument.class);

    assertThat(deserialised.getDateOfIssue()).isEqualTo(original);
  }

  // ─── DocumentType JSON serialisation ─────────────────────────────────────

  @Test
  void documentType_itahc_serialisesToUppercaseString() throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(DocumentType.ITAHC);
    assertThat(json).isEqualTo("\"ITAHC\"");
  }

  @Test
  void documentType_itahc_deserialisesFromUppercaseString() throws JsonProcessingException {
    DocumentType type = objectMapper.readValue("\"ITAHC\"", DocumentType.class);
    assertThat(type).isEqualTo(DocumentType.ITAHC);
  }

  @Test
  void documentType_unknownValue_throwsInvalidFormatException() {
    assertThatThrownBy(() -> objectMapper.readValue("\"UNKNOWN\"", DocumentType.class))
        .isInstanceOf(InvalidFormatException.class);
  }

  // ─── ScanStatus JSON serialisation ───────────────────────────────────────

  @Test
  void scanStatus_pending_serialisesToUppercaseString() throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(ScanStatus.PENDING);
    assertThat(json).isEqualTo("\"PENDING\"");
  }

  @Test
  void scanStatus_complete_serialisesToUppercaseString() throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(ScanStatus.COMPLETE);
    assertThat(json).isEqualTo("\"COMPLETE\"");
  }

  @Test
  void scanStatus_rejected_serialisesToUppercaseString() throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(ScanStatus.REJECTED);
    assertThat(json).isEqualTo("\"REJECTED\"");
  }

  @Test
  void scanStatus_pending_deserialisesFromUppercaseString() throws JsonProcessingException {
    ScanStatus status = objectMapper.readValue("\"PENDING\"", ScanStatus.class);
    assertThat(status).isEqualTo(ScanStatus.PENDING);
  }

  @Test
  void scanStatus_complete_deserialisesFromUppercaseString() throws JsonProcessingException {
    ScanStatus status = objectMapper.readValue("\"COMPLETE\"", ScanStatus.class);
    assertThat(status).isEqualTo(ScanStatus.COMPLETE);
  }

  @Test
  void scanStatus_rejected_deserialisesFromUppercaseString() throws JsonProcessingException {
    ScanStatus status = objectMapper.readValue("\"REJECTED\"", ScanStatus.class);
    assertThat(status).isEqualTo(ScanStatus.REJECTED);
  }

  @Test
  void scanStatus_unknownValue_throwsInvalidFormatException() {
    assertThatThrownBy(() -> objectMapper.readValue("\"UNKNOWN\"", ScanStatus.class))
        .isInstanceOf(InvalidFormatException.class);
  }
}
