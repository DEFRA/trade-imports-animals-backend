package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTypeTest {

  private static final List<String> FRONTEND_DOCUMENT_TYPES = List.of(
      "ITAHC",
      "VETERINARY_HEALTH_CERTIFICATE",
      "SELF_DECLARATION",
      "JOURNEY_LOG",
      "IMPORT_PERMIT",
      "CITES_PERMIT_OR_CERTIFICATE",
      "AIR_WAYBILL",
      "SEA_WAYBILL",
      "RAIL_WAYBILL",
      "BILL_OF_LADING",
      "COMMERCIAL_INVOICE",
      "PACKING_OR_SPECIES_LIST",
      "OTHER");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldHoldTheFrontendCodesInTheFrontendOrderAndParseEveryOne()
      throws JsonProcessingException {
    assertThat(DocumentType.values())
        .extracting(Enum::name)
        .containsExactlyElementsOf(FRONTEND_DOCUMENT_TYPES);

    for (String documentType : FRONTEND_DOCUMENT_TYPES) {
      assertThat(objectMapper.readValue("\"" + documentType + "\"", DocumentType.class))
          .isEqualTo(DocumentType.valueOf(documentType));
    }
  }
}
