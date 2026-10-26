package uk.gov.defra.trade.imports.animals.accompanyingdocument;

final class UploadSessionFixtures {

  static final String UPLOAD_ID = "upload-abc-123";
  static final String CORRELATION_ID = "correlation-abc-123";

  private UploadSessionFixtures() {
  }

  static RegisterUploadRequest registerUploadRequest() {
    return new RegisterUploadRequest(UPLOAD_ID, CORRELATION_ID);
  }

  static UploadSession registeredSession(String notificationReferenceNumber) {
    return UploadSession.builder()
        .notificationReferenceNumber(notificationReferenceNumber)
        .uploadId(UPLOAD_ID)
        .correlationId(CORRELATION_ID)
        .build();
  }
}
