package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTypeTest {

  private static final List<String> DOCUMENT_TYPES = List.of(
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
      "OTHER",
      "CARGO_MANIFEST",
      "INSPECTION_CERTIFICATE",
      "PHYTOSANITARY_CERTIFICATE",
      "ORIGIN_CERTIFICATE",
      "HEAT_TREATMENT_CERTIFICATE",
      "CONTAINER_MANIFEST",
      "CUSTOMS_DECLARATION",
      "CONFORMITY_CERTIFICATE");

  /**
   * The complete plant-products frontend allowlist, mirroring
   * {@code sets/plant-products/services/reference/document-types.js}. A frontend option added
   * without a matching enum constant fails here rather than at runtime.
   */
  private static final List<String> PLANT_PRODUCTS_FRONTEND_DOCUMENT_TYPES = List.of(
      "AIR_WAYBILL",
      "COMMERCIAL_INVOICE",
      "CARGO_MANIFEST",
      "INSPECTION_CERTIFICATE",
      "PHYTOSANITARY_CERTIFICATE",
      "IMPORT_PERMIT",
      "ORIGIN_CERTIFICATE",
      "LETTER_OF_AUTHORITY",
      "HEAT_TREATMENT_CERTIFICATE",
      "CONTAINER_MANIFEST",
      "SEA_WAYBILL",
      "RAIL_WAYBILL",
      "CUSTOMS_DECLARATION",
      "BILL_OF_LADING",
      "CONFORMITY_CERTIFICATE",
      "OTHER");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldExposeTheCompleteOrderedSetAndParseEveryFrontendCode()
      throws JsonProcessingException {
    assertThat(DocumentType.values())
        .extracting(Enum::name)
        .containsExactlyElementsOf(DOCUMENT_TYPES);

    for (String documentType : DOCUMENT_TYPES) {
      assertThat(objectMapper.readValue("\"" + documentType + "\"", DocumentType.class))
          .isEqualTo(DocumentType.valueOf(documentType));
    }
  }

  @Test
  void shouldAcceptEveryPlantProductsFrontendDocumentType() {
    assertThat(DOCUMENT_TYPES).containsAll(PLANT_PRODUCTS_FRONTEND_DOCUMENT_TYPES);
  }

  @Test
  void shouldRejectAnUnknownDocumentType() {
    assertThatThrownBy(() -> objectMapper.readValue("\"NOT_A_DOCUMENT_TYPE\"", DocumentType.class))
        .isInstanceOf(InvalidFormatException.class);
  }
}
