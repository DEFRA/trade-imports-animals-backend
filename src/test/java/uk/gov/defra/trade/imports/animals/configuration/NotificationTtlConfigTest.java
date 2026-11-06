package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
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

    private static Set<ConstraintViolation<NotificationTtlConfig>> violationsOf(NotificationTtlConfig config) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(config);
        }
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
        assertThat(violationsOf(withDays(days)))
            .singleElement()
            .satisfies(violation ->
                assertThat(violation.getPropertyPath()).hasToString("days"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, 7, 90})
    void days_shouldPassValidation_whenPositiveOrUnset(Integer days) {
        assertThat(violationsOf(withDays(days))).isEmpty();
    }
}
