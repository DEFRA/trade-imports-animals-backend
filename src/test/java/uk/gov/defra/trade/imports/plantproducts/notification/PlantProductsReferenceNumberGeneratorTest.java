package uk.gov.defra.trade.imports.plantproducts.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlantProductsReferenceNumberGeneratorTest {

    private PlantProductsReferenceNumberGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new PlantProductsReferenceNumberGenerator();
    }

    @Nested
    class Generate {

        @Test
        void generate_shouldMatchPublishedPattern() {
            // When
            String result = generator.generate();

            // Then
            assertThat(result).matches(PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        }

        @Test
        void generate_shouldContainCurrentTwoDigitYear() {
            // Given
            String currentYear = String.format("%02d", LocalDate.now().getYear() % 100);

            // When
            String result = generator.generate();

            // Then
            assertThat(result).startsWith("GBN-PP-" + currentYear + "-");
        }

        @Test
        void generate_shouldUseOnlyCrockfordCharacters_andShowRandomness() {
            // Given
            Set<String> references = new HashSet<>();

            // When
            for (int i = 0; i < 100; i++) {
                references.add(generator.generate());
            }

            // Then
            assertThat(references).allMatch(reference ->
                reference.matches(PlantProductsReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN));
            assertThat(references).noneMatch(reference ->
                reference.substring(reference.lastIndexOf('-') + 1).matches(".*[ILOU].*"));
            assertThat(references).hasSizeGreaterThan(1);
        }
    }
}
