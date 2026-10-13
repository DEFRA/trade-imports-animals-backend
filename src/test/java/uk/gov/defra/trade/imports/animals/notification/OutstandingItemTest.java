package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.ScanStatus;

class OutstandingItemTest {

    private static AccompanyingDocument document(DocumentType type, ScanStatus scanStatus) {
        return AccompanyingDocument.builder()
            .documentType(type)
            .scanStatus(scanStatus)
            .build();
    }

    @Test
    void outstandingFor_isEmpty_whenACleanItahcIsUploaded() {
        assertThat(OutstandingItem.outstandingFor(
            List.of(document(DocumentType.ITAHC, ScanStatus.COMPLETE)))).isEmpty();
    }

    @Test
    void outstandingFor_isEmpty_whenACleanHealthCertificateIsUploaded() {
        assertThat(OutstandingItem.outstandingFor(
            List.of(document(DocumentType.HEALTH_CERTIFICATE, ScanStatus.COMPLETE)))).isEmpty();
    }

    @Test
    void outstandingFor_owesTheHealthCertificate_whenNoDocumentIsUploaded() {
        assertThat(OutstandingItem.outstandingFor(List.of()))
            .containsExactly(OutstandingItem.HEALTH_CERTIFICATE);
        assertThat(OutstandingItem.outstandingFor(null))
            .containsExactly(OutstandingItem.HEALTH_CERTIFICATE);
    }

    @Test
    void outstandingFor_owesTheHealthCertificate_whenOnlyAVeterinaryHealthCertificateIsUploaded() {
        assertThat(OutstandingItem.outstandingFor(
            List.of(document(DocumentType.VETERINARY_HEALTH_CERTIFICATE, ScanStatus.COMPLETE))))
            .containsExactly(OutstandingItem.HEALTH_CERTIFICATE);
    }

    @Test
    void outstandingFor_owesTheHealthCertificate_whenTheItahcIsStillScanningOrRejected() {
        assertThat(OutstandingItem.outstandingFor(List.of(
            document(DocumentType.ITAHC, ScanStatus.PENDING),
            document(DocumentType.HEALTH_CERTIFICATE, ScanStatus.REJECTED))))
            .containsExactly(OutstandingItem.HEALTH_CERTIFICATE);
    }
}
