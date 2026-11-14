package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;

class OutstandingItemTest {

    @Test
    void outstandingAmong_shouldOweTheHealthCertificate_whenThereAreNoDocuments() {
        assertThat(OutstandingItem.outstandingAmong(List.of()))
            .containsExactly(OutstandingItem.HEALTH_CERTIFICATE);
    }

    @Test
    void outstandingAmong_shouldOweTheHealthCertificate_whenOnlyAVeterinaryCertificateWasUploaded() {
        List<AccompanyingDocument> documents = List.of(document(DocumentType.VETERINARY_HEALTH_CERTIFICATE));

        assertThat(OutstandingItem.outstandingAmong(documents))
            .containsExactly(OutstandingItem.HEALTH_CERTIFICATE);
    }

    @Test
    void outstandingAmong_shouldOweNothing_whenAnItahcIsAmongTheDocuments() {
        List<AccompanyingDocument> documents = List.of(
            document(DocumentType.COMMERCIAL_INVOICE), document(DocumentType.ITAHC));

        assertThat(OutstandingItem.outstandingAmong(documents)).isEmpty();
    }

    @Test
    void submittedStatusFor_shouldGiveSubmittedActionRequired_whenTheHealthCertificateIsOutstanding() {
        assertThat(OutstandingItem.submittedStatusFor(List.of(OutstandingItem.HEALTH_CERTIFICATE)))
            .isEqualTo(NotificationStatus.SUBMITTED_ACTION_REQUIRED);
    }

    @Test
    void submittedStatusFor_shouldGiveSubmitted_whenNothingIsOutstandingOrNothingWasRecorded() {
        assertThat(OutstandingItem.submittedStatusFor(List.of())).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(OutstandingItem.submittedStatusFor(null)).isEqualTo(NotificationStatus.SUBMITTED);
    }

    private static AccompanyingDocument document(DocumentType type) {
        return AccompanyingDocument.builder().documentType(type).build();
    }
}
