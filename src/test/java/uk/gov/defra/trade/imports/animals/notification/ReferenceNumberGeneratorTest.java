package uk.gov.defra.trade.imports.animals.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReferenceNumberGeneratorTest {

    private static final String CURRENT_YY = String.format("%02d", LocalDate.now().getYear() % 100);
    private static final String CROCKFORD_BODY_REGEX = "[0-9A-HJ-KM-NP-TV-Z]{6}";
    private static final String FULL_FORMAT_REGEX = "GBN-AG-\\d{2}-" + CROCKFORD_BODY_REGEX;

    private ReferenceNumberGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ReferenceNumberGenerator();
    }

    @Nested
    class Generate {

        @Test
        void generate_shouldReturnReferenceNumberInCorrectFormat() {
            // When
            String result = generator.generate();

            // Then
            assertThat(result).matches(FULL_FORMAT_REGEX);
        }

        @Test
        void generate_shouldIncludeCurrentTwoDigitYear() {
            // When
            String result = generator.generate();

            // Then
            assertThat(result).startsWith("GBN-AG-" + CURRENT_YY + "-");
        }

        @Test
        void generate_shouldProduceSixCharacterCrockfordBody_excludingILOU() {
            // When — sample 100 reference numbers; probability of missing an invalid char by chance is negligible
            for (int i = 0; i < 100; i++) {
                String ref = generator.generate();
                String body = ref.substring(ref.lastIndexOf('-') + 1);

                // Then
                assertThat(body)
                    .hasSize(6)
                    .matches(CROCKFORD_BODY_REGEX)
                    .doesNotContain("I", "L", "O", "U");
            }
        }
    }
}
