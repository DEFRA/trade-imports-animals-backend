package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.AccompanyingDocument;
import uk.gov.defra.trade.imports.animals.accompanyingdocument.DocumentType;

class OutstandingItemTest {

    @Test
    void outstandingAmong_shouldOweTheItahc_whenThereAreNoDocuments() {
        assertThat(OutstandingItem.outstandingAmong(List.of()))
            .containsExactly(OutstandingItem.ITAHC);
    }

    @Test
    void outstandingAmong_shouldOweTheItahc_whenOnlyAVeterinaryCertificateWasUploaded() {
        List<AccompanyingDocument> documents = List.of(document(DocumentType.VETERINARY_HEALTH_CERTIFICATE));

        assertThat(OutstandingItem.outstandingAmong(documents))
            .containsExactly(OutstandingItem.ITAHC);
    }

    @Test
    void outstandingAmong_shouldOweNothing_whenAnItahcIsAmongTheDocuments() {
        List<AccompanyingDocument> documents = List.of(
            document(DocumentType.COMMERCIAL_INVOICE), document(DocumentType.ITAHC));

        assertThat(OutstandingItem.outstandingAmong(documents)).isEmpty();
    }

    private static AccompanyingDocument document(DocumentType type) {
        return AccompanyingDocument.builder().documentType(type).build();
    }
}
