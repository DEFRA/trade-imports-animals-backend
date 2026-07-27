package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig.Sweep;

class NotificationTtlConfigTest {

    private static NotificationTtlConfig withEnvironment(String environment) {
        return new NotificationTtlConfig(7, environment,
            new Sweep(false, 3_600_000, 10, Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    private static NotificationTtlConfig withDays(Integer days) {
        return new NotificationTtlConfig(days, "dev",
            new Sweep(false, 3_600_000, 10, Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "PROD", "Prod"})
    void isProd_true_forProdRegardlessOfCase(String environment) {
        assertThat(withEnvironment(environment).isProd()).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"local", "dev", "test", "perf-test", "ext-test", "production"})
    void isProd_false_forEveryNonProdEnvironment(String environment) {
        assertThat(withEnvironment(environment).isProd()).isFalse();
    }

    @Test
    void sweep_appliesSafeDefaults_forNonPositiveOrNullValues() {
        Sweep sweep = new Sweep(true, 0, 0, null, null);

        assertThat(sweep.batchSize()).isEqualTo(10);
        assertThat(sweep.intervalMs()).isEqualTo(Duration.ofHours(1).toMillis());
        assertThat(sweep.lockAtLeastFor()).isEqualTo(Duration.ofSeconds(1));
        assertThat(sweep.lockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void sweep_keepsProvidedValues_whenValid() {
        Sweep sweep = new Sweep(
            true, 5_000, 25, Duration.ofSeconds(2), Duration.ofSeconds(60));

        assertThat(sweep.batchSize()).isEqualTo(25);
        assertThat(sweep.intervalMs()).isEqualTo(5_000);
        assertThat(sweep.lockAtLeastFor()).isEqualTo(Duration.ofSeconds(2));
        assertThat(sweep.lockAtMostFor()).isEqualTo(Duration.ofSeconds(60));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -7})
    void days_shouldFailValidation_whenNotPositive(int days) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(withDays(days)))
                .singleElement()
                .satisfies(violation ->
                    assertThat(violation.getPropertyPath()).hasToString("days"));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, 7, 90})
    void days_shouldPassValidation_whenPositiveOrUnset(Integer days) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(withDays(days))).isEmpty();
        }
    }
}
