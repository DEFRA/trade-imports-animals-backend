package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionFixtures.CORRELATION_ID;
import static uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionFixtures.UPLOAD_ID;
import static uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionFixtures.registerUploadRequest;
import static uk.gov.defra.trade.imports.animals.accompanyingdocument.UploadSessionFixtures.registeredSession;
import static uk.gov.defra.trade.imports.animals.utils.LogCapture.logEventsDuring;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.FileStatus;
import uk.gov.defra.trade.imports.animals.s3.S3DocumentService;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.file.UploadedFile;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultForm;
import uk.gov.defra.trade.imports.animals.cdp.uploader.CdpScanResultPayload;
import uk.gov.defra.trade.imports.animals.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.animals.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.animals.filter.TraderOrganisationHeaderFilter;
import uk.gov.defra.trade.imports.animals.notification.NotificationService;

@WebMvcTest(DocumentController.class)
@EnableConfigurationProperties(AppConfig.class)
@TestPropertySource(properties = {
    "cdp.tracing.header-name=x-cdp-request-id",
    "app.base-url=http://localhost:8085",
    "outbox.sns.topic-arn=arn:aws:sns:eu-west-2:000000000000:unit-test-outbox.fifo"
})
class DocumentControllerTest {

  private static final String CALLER_ORGANISATION_ID = "5900002";
  private static final String NOTIFICATION_REFERENCE_NUMBER = "GBN-AG-26-000001";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private DocumentService documentService;

  @MockitoBean
  private NotificationService notificationService;

  @MockitoBean
  private S3DocumentService s3DocumentService;

  @BeforeEach
  void stubUploadSessionNotificationReferenceNumber() {
    when(documentService.notificationReferenceNumberForUploadId(anyString()))
        .thenReturn(NOTIFICATION_REFERENCE_NUMBER);
  }

  @Nested
  class Register {

    @Test
    void shouldReturn201WithLocationHeaderAndTheRegisteredSession() throws Exception {
      when(documentService.registerUpload(
          eq(NOTIFICATION_REFERENCE_NUMBER), any(RegisterUploadRequest.class)))
          .thenReturn(registeredSession(NOTIFICATION_REFERENCE_NUMBER));

      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", NOTIFICATION_REFERENCE_NUMBER)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .content(objectMapper.writeValueAsString(registerUploadRequest())))
          .andExpect(status().isCreated())
          .andExpect(header().string(
              "Location",
              "http://localhost:8085/document-uploads/" + UPLOAD_ID))
          .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID))
          .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
          .andExpect(
              jsonPath("$.notificationReferenceNumber").value(NOTIFICATION_REFERENCE_NUMBER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GBN-AG-26-OTHER0", "GBN-HRP-26-000001", "LOGGING-TEST"})
    void shouldReturn400_whenTheReferenceIsNotANotificationReferenceNumber(String reference)
        throws Exception {
      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", reference)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(registerUploadRequest())))
          .andExpect(status().isBadRequest());

      verify(documentService, never()).registerUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenUploadIdIsBlank() throws Exception {
      String body = """
          {"uploadId":"","correlationId":"correlation-abc-123"}
          """;

      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", NOTIFICATION_REFERENCE_NUMBER)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.uploadId").exists());

      verify(documentService, never()).registerUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenCorrelationIdIsMissing() throws Exception {
      String body = """
          {"uploadId":"upload-abc-123"}
          """;

      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", NOTIFICATION_REFERENCE_NUMBER)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.correlationId").exists());

      verify(documentService, never()).registerUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenUploadIdExceeds100Chars() throws Exception {
      String body = String.format(
          "{\"uploadId\":\"%s\",\"correlationId\":\"correlation-abc-123\"}", "A".repeat(101));

      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", NOTIFICATION_REFERENCE_NUMBER)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.uploadId").exists());

      verify(documentService, never()).registerUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenCorrelationIdExceeds100Chars() throws Exception {
      String body = String.format(
          "{\"uploadId\":\"upload-abc-123\",\"correlationId\":\"%s\"}", "A".repeat(101));

      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", NOTIFICATION_REFERENCE_NUMBER)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.correlationId").exists());

      verify(documentService, never()).registerUpload(any(), any());
    }

    @Test
    void shouldReturn404_whenCallerDoesNotOwnTheNotification() throws Exception {
      doThrow(new NotFoundException(
          "Cannot find notification with reference number: " + NOTIFICATION_REFERENCE_NUMBER))
          .when(notificationService)
          .requireTraderAccess(NOTIFICATION_REFERENCE_NUMBER, CALLER_ORGANISATION_ID);

      mockMvc.perform(post("/notifications/{referenceNumber}/document-uploads", NOTIFICATION_REFERENCE_NUMBER)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .content(objectMapper.writeValueAsString(registerUploadRequest())))
          .andExpect(status().isNotFound());

      verify(documentService, never()).registerUpload(any(), any());
    }
  }

  @Nested
  class Claim {

    private AccompanyingDocument claimedWith(DocumentType documentType) {
      return AccompanyingDocument.builder()
          .uploadId(UPLOAD_ID)
          .documentType(documentType)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
          .scanStatus(ScanStatus.PENDING)
          .claimed(true)
          .build();
    }

    private void givenClaimAccepted() {
      when(documentService.claimUpload(eq(UPLOAD_ID), any(ClaimUploadRequest.class)))
          .thenReturn(claimedWith(DocumentType.ITAHC));
    }

    private String claimBody(String documentReference) {
      return String.format(
          "{\"documentType\":\"ITAHC\",\"documentReference\":\"%s\","
              + "\"dateOfIssue\":\"2026-01-15\"}",
          documentReference);
    }

    @Test
    void shouldReturn200WithTheClaimedDocument() throws Exception {
      ClaimUploadRequest request = new ClaimUploadRequest(
          DocumentType.ITAHC, "UKGB2026001", LocalDate.parse("2026-01-15"));

      givenClaimAccepted();

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID))
          .andExpect(jsonPath("$.claimed").value(true))
          .andExpect(jsonPath("$.documentReference").value("UKGB2026001"));
    }

    @Test
    void shouldPassTheSubmittedTypeReferenceAndDateToClaimUploadUnchanged() throws Exception {
      ClaimUploadRequest expectedRequest = new ClaimUploadRequest(
          DocumentType.AIR_WAYBILL, "UKGB2026001", LocalDate.parse("2026-01-15"));
      String body = """
          {
            "documentType":"AIR_WAYBILL",
            "documentReference":"UKGB2026001",
            "dateOfIssue":"2026-01-15"
          }
          """;

      when(documentService.claimUpload(UPLOAD_ID, expectedRequest))
          .thenReturn(claimedWith(DocumentType.AIR_WAYBILL));

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());

      verify(documentService).claimUpload(UPLOAD_ID, expectedRequest);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "125-12345675",
        "CHEDA.GB.2026.1234567",
        "INV 2026/0042",
        "UKGB2026001234"
    })
    void shouldAcceptAReferenceCarryingTheSeparatorsRealDocumentsUse(String reference)
        throws Exception {
      givenClaimAccepted();

      String body = claimBody(reference);

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REF<script>", "REF;DROP", "REF|pipe", "REF\\\\back"})
    void shouldReturn400_whenAReferenceCarriesACharacterOutsideTheRule(String reference)
        throws Exception {
      String body = claimBody(reference);

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentReference").exists());
    }

    @Test
    void shouldReturn400_whenDocumentReferenceIsBlank() throws Exception {
      String body = claimBody("");

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentReference").exists());
    }

    @Test
    void shouldReturn200_whenDocumentReferenceIs58Chars() throws Exception {
      String body = claimBody("A".repeat(58));

      givenClaimAccepted();

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());
    }

    @Test
    void shouldReturn400_whenDocumentReferenceIs59Chars() throws Exception {
      String body = claimBody("A".repeat(59));

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentReference").exists());
    }

    @Test
    void shouldReturn400_whenDocumentTypeIsNull() throws Exception {
      String body = """
          {"documentReference":"UKGB2026001","dateOfIssue":"2026-01-15"}
          """;

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.documentType").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-01-15T00:00:00Z", "2026-01-15T00:00:00", "2026-01-15T00:00:00+01:00"})
    void shouldReturn400_whenDateOfIssueCarriesATimeOrAnOffset(String dateOfIssue)
        throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"%s"}
          """.formatted(dateOfIssue);

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.type")
              .value("https://api.cdp.defra.cloud/problems/malformed-request"))
          .andExpect(jsonPath("$.title").value("Malformed Request"))
          .andExpect(jsonPath("$.detail")
              .value(Matchers.containsString("each date-only field is a date, for example 2026-12-12")));

      verify(documentService, never()).claimUpload(any(), any());
    }

    @Test
    void shouldReturn400_whenDateOfIssueIsNull() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001"}
          """;

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.dateOfIssue").exists());
    }

    @Test
    void shouldIgnoreUnknownFieldsLikeRedirectUrl() throws Exception {
      String body = """
          {"documentType":"ITAHC","documentReference":"UKGB2026001","dateOfIssue":"2026-01-15","redirectUrl":"/anything"}
          """;

      givenClaimAccepted();

      mockMvc.perform(post("/document-uploads/{uploadId}/claim", UPLOAD_ID)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isOk());

      ArgumentCaptor<ClaimUploadRequest> requestCaptor =
          ArgumentCaptor.forClass(ClaimUploadRequest.class);
      verify(documentService).claimUpload(eq(UPLOAD_ID), requestCaptor.capture());
      ClaimUploadRequest captured = requestCaptor.getValue();
      assertThat(captured.documentType()).isEqualTo(DocumentType.ITAHC);
      assertThat(captured.documentReference()).isEqualTo("UKGB2026001");
      assertThat(captured.dateOfIssue()).isEqualTo(LocalDate.parse("2026-01-15"));
    }
  }
  @Nested
  class ListDocuments {

    @Test
    void shouldReturn200WithDocumentList() throws Exception {
      String notificationReferenceNumber = "GBN-AG-26-000001";

      AccompanyingDocument doc = AccompanyingDocument.builder()
          .id("doc-id-1")
          .notificationReferenceNumber(notificationReferenceNumber)
          .uploadId("upload-abc-123")
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
          .scanStatus(ScanStatus.COMPLETE)
          .files(List.of())
          .build();

      when(documentService.findByNotificationReferenceNumber(notificationReferenceNumber))
          .thenReturn(List.of(doc));

      mockMvc.perform(get("/notifications/{referenceNumber}/document-uploads", notificationReferenceNumber)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items").isArray())
          .andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].uploadId").value("upload-abc-123"))
          .andExpect(jsonPath("$.items[0].dateOfIssue").value("2026-01-15"))
          .andExpect(jsonPath("$.items[0].scanStatus").value("COMPLETE"));
    }

    @Test
    void shouldReturn200WithEmptyList() throws Exception {
      String notificationReferenceNumber = "GBN-AG-26-000001";
      when(documentService.findByNotificationReferenceNumber(notificationReferenceNumber))
          .thenReturn(List.of());

      mockMvc.perform(get("/notifications/{referenceNumber}/document-uploads", notificationReferenceNumber)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items").isArray())
          .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void shouldReturn404_whenCallerDoesNotOwnTheNotification() throws Exception {
      String notificationReferenceNumber = "GBN-AG-26-000001";
      doThrow(new NotFoundException(
          "Cannot find notification with reference number: " + notificationReferenceNumber))
          .when(notificationService)
          .requireTraderAccess(notificationReferenceNumber, CALLER_ORGANISATION_ID);

      mockMvc.perform(get("/notifications/{referenceNumber}/document-uploads", notificationReferenceNumber)
              .contentType(MediaType.APPLICATION_JSON)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isNotFound());

      verify(documentService, never()).findByNotificationReferenceNumber(anyString());
    }
  }

  @Nested
  class GetByUploadId {

    @Test
    void shouldReturn200WithDocument() throws Exception {
      String uploadId = "upload-abc-123";

      AccompanyingDocument doc = AccompanyingDocument.builder()
          .id("doc-id-1")
          .notificationReferenceNumber("GBN-AG-26-000001")
          .uploadId(uploadId)
          .documentType(DocumentType.ITAHC)
          .documentReference("UKGB2026001")
          .dateOfIssue(LocalDate.parse("2026-01-15"))
          .scanStatus(ScanStatus.PENDING)
          .files(List.of())
          .build();

      when(documentService.findByUploadId(uploadId)).thenReturn(doc);

      mockMvc.perform(get("/document-uploads/{uploadId}", uploadId)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.uploadId").value(uploadId))
          .andExpect(jsonPath("$.documentType").value("ITAHC"))
          .andExpect(jsonPath("$.dateOfIssue").value("2026-01-15"))
          .andExpect(jsonPath("$.scanStatus").value("PENDING"));
    }

    @Test
    void shouldReturn404_whenUploadIdUnknown() throws Exception {
      String unknownId = "unknown-upload-id";
      when(documentService.findByUploadId(unknownId))
          .thenThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId));

      mockMvc.perform(get("/document-uploads/{uploadId}", unknownId)
              .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID)
              .contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + unknownId));
    }
  }

  @Nested
  class ScanResult {

    private static CdpScanResultPayload payloadWithMetadata(Map<String, String> metadata) {
      return new CdpScanResultPayload("ready", metadata, new CdpScanResultForm(Map.of()), 0);
    }

    private ResultActions postScanResult(CdpScanResultPayload payload) throws Exception {
      return mockMvc.perform(post("/document-uploads/{pathSegment}/scan-results", "pending")
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(payload)));
    }

    @Test
    void shouldLogTheCorrelationIdTheCallbackCarries() {
      CdpScanResultPayload payload = payloadWithMetadata(Map.of("correlationId", CORRELATION_ID));

      List<ILoggingEvent> events = logEventsDuring(DocumentController.class, () -> {
        try {
          postScanResult(payload);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });

      assertThat(events).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
          "POST /document-uploads/pending/scan-results correlationId=" + CORRELATION_ID
              + " uploadStatus=ready");
    }

    @Test
    void shouldReturn204_whenTheServiceAcceptsTheScanResult() throws Exception {
      doNothing().when(documentService).handleScanResult(any(CdpScanResultPayload.class));

      postScanResult(payloadWithMetadata(Map.of("correlationId", CORRELATION_ID)))
          .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturn404_whenTheServiceFindsNoUploadSession() throws Exception {
      String unknownCorrelationId = "unknown-correlation-id";
      doThrow(new NotFoundException(
          "No upload session found with correlationId: " + unknownCorrelationId))
          .when(documentService).handleScanResult(any(CdpScanResultPayload.class));

      postScanResult(payloadWithMetadata(Map.of("correlationId", unknownCorrelationId)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No upload session found with correlationId: " + unknownCorrelationId));
    }

    @Test
    void shouldReturn400_whenTheServiceRefusesTheScanResult() throws Exception {
      doThrow(new BadRequestException(
          "Scan callback missing required correlationId in metadata"))
          .when(documentService).handleScanResult(any(CdpScanResultPayload.class));

      postScanResult(payloadWithMetadata(Map.of()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.detail").value(
              "Scan callback missing required correlationId in metadata"));
    }
  }

  @Nested
  class DownloadFile {

    @Test
    void shouldReturn200WithStreamBody() throws Exception {
      String uploadId = "upload-abc-123";
      byte[] fileContent = "PDF file content".getBytes();

      UploadedFile uploadedFile = new UploadedFile(
          "test-doc.pdf",
          "application/pdf",
          (long) fileContent.length,
          uploadId + "/some-internal-file-id",
          "documents-bucket",
          FileStatus.COMPLETE,
          "sha256abc",
          "application/pdf",
          false,
          null,
          null);

      when(documentService.findFile(uploadId)).thenReturn(uploadedFile);
      doNothing().when(s3DocumentService).streamToOutput(any(String.class), any());

      mockMvc.perform(get("/document-uploads/{uploadId}/file", uploadId)
          .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isOk())
          .andExpect(header().string(
              "Content-Disposition",
              "attachment; filename=\"=?UTF-8?Q?test-doc.pdf?=\"; filename*=UTF-8''test-doc.pdf"))
          .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void shouldFallBackToOctetStream_whenStoredContentTypeIsMalformed() throws Exception {
      String uploadId = "upload-abc-123";
      byte[] fileContent = "binary".getBytes();
      UploadedFile uploadedFile = new UploadedFile(
          "weird.bin",
          "not/a valid/media type",
          (long) fileContent.length,
          uploadId + "/file-id",
          "documents-bucket",
          FileStatus.COMPLETE,
          "sha256abc",
          "application/octet-stream",
          false,
          null,
          null);
      when(documentService.findFile(uploadId)).thenReturn(uploadedFile);
      doNothing().when(s3DocumentService).streamToOutput(any(String.class), any());

      mockMvc.perform(get("/document-uploads/{uploadId}/file", uploadId)
          .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Type", "application/octet-stream"));
    }

    @Test
    void shouldReturn404_whenUploadIdUnknown() throws Exception {
      String unknownId = "unknown-upload-id";
      when(documentService.findFile(unknownId))
          .thenThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId));

      mockMvc.perform(get("/document-uploads/{uploadId}/file", unknownId)
          .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + unknownId));
    }
  }

  @Nested
  class Delete {

    @Test
    void shouldReturn204() throws Exception {
      String uploadId = "upload-abc-123";
      doNothing().when(documentService).deleteByUploadId(uploadId);

      mockMvc.perform(delete("/document-uploads/{uploadId}", uploadId)
          .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturn404_whenUnknown() throws Exception {
      String unknownId = "unknown-upload-id";
      doThrow(new NotFoundException("No accompanying document found with uploadId: " + unknownId))
          .when(documentService).deleteByUploadId(unknownId);

      mockMvc.perform(delete("/document-uploads/{uploadId}", unknownId)
          .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, CALLER_ORGANISATION_ID))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.detail").value(
              "No accompanying document found with uploadId: " + unknownId));
    }
  }
}
