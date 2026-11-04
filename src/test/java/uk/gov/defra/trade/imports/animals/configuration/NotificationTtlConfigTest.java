package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationTtlConfigTest {

    private static NotificationTtlConfig withEnvironment(String environment) {
        return new NotificationTtlConfig(7, environment);
    }

    private static NotificationTtlConfig withDays(Integer days) {
        return new NotificationTtlConfig(days, "dev");
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
