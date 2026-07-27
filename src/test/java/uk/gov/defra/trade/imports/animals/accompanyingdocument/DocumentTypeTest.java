package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentTypeTest {

  @Test
  void parse_shouldReturnItahc_whenRawMatchesEnumName() {
    assertThat(DocumentType.parse("ITAHC")).contains(DocumentType.ITAHC);
  }

  @Test
  void parse_shouldReturnVeterinaryHealthCertificate_whenRawMatchesEnumName() {
    assertThat(DocumentType.parse("VETERINARY_HEALTH_CERTIFICATE"))
        .contains(DocumentType.VETERINARY_HEALTH_CERTIFICATE);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", " ", "\t", "\n" })
  void parse_shouldReturnEmpty_whenRawIsNullOrBlank(String raw) {
    assertThat(DocumentType.parse(raw)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "itahc",                            // lowercase — enum name is uppercase
      "Itahc",                            // mixed case
      "ITAHC ",                           // trailing whitespace (valueOf is strict)
      "COMMERCIAL_INVOICE",               // not a declared value
      "UNKNOWN",                          // not a declared value
      "  VETERINARY_HEALTH_CERTIFICATE  " // whitespace-padded but otherwise valid — reject
  })
  void parse_shouldReturnEmpty_whenRawDoesNotMatchAnyEnumConstant(String raw) {
    assertThat(DocumentType.parse(raw)).isEmpty();
  }

  @Test
  void parse_shouldReturnEmpty_whenRawIsNumericString() {
    // Guarding against a caller that passes an accidentally-serialised enum ordinal.
    assertThat(DocumentType.parse("0")).isEmpty();
  }
}
