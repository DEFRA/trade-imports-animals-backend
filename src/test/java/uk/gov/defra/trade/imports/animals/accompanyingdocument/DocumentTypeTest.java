package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTypeTest {

  private static final List<String> V4_DOCUMENT_TYPES = List.of(
      "ITAHC",
      "VETERINARY_HEALTH_CERTIFICATE",
      "AIR_WAYBILL",
      "IMPORT_PERMIT",
      "LETTER_OF_AUTHORITY",
      "COMMERCIAL_INVOICE",
      "SEA_WAYBILL",
      "RAIL_WAYBILL",
      "BILL_OF_LADING",
      "CATCH_CERTIFICATE",
      "LABORATORY_SAMPLING_RESULTS_FOR_AFLATOXIN",
      "HEALTH_CERTIFICATE",
      "JOURNEY_LOG",
      "OTHER");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldExposeTheCompleteOrderedV4SetAndParseEveryFrontendCode()
      throws JsonProcessingException {
    assertThat(DocumentType.values())
        .extracting(Enum::name)
        .containsExactlyElementsOf(V4_DOCUMENT_TYPES);

    for (String documentType : V4_DOCUMENT_TYPES) {
      assertThat(objectMapper.readValue("\"" + documentType + "\"", DocumentType.class))
          .isEqualTo(DocumentType.valueOf(documentType));
    }
  }
}
