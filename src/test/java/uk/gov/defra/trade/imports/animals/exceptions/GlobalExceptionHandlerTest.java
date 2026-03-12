package uk.gov.defra.trade.imports.animals.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void handleValidationException_shouldReturnBadRequestWithFieldErrors() {
        // Given
        String traceId = "test-trace-123";
        MDC.put("trace.id", traceId);

        MethodArgumentNotValidException exception = createValidationException(
            new FieldError("notification", "origin", "must not be null"),
            new FieldError("notification", "commodity", "must not be blank")
        );

        // When
        ProblemDetail problemDetail = exceptionHandler.handleValidationException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Validation Error");
        assertThat(problemDetail.getDetail()).isEqualTo("Validation failed for one or more fields");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/validation-error"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) problemDetail.getProperties().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get("origin")).isEqualTo("must not be null");
        assertThat(errors.get("commodity")).isEqualTo("must not be blank");
    }

    @Test
    void handleValidationException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        MethodArgumentNotValidException exception = createValidationException(
            new FieldError("notification", "origin", "must not be null")
        );

        // When
        ProblemDetail problemDetail = exceptionHandler.handleValidationException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    @Test
    void handleNotFoundException_shouldReturnNotFound() {
        // Given
        String traceId = "test-trace-456";
        MDC.put("trace.id", traceId);
        NotFoundException exception = new NotFoundException("Notification with id 12345 not found");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleNotFoundException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problemDetail.getDetail()).isEqualTo("Notification with id 12345 not found");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/not-found"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleNotFoundException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        NotFoundException exception = new NotFoundException("Resource not found");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleNotFoundException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    @Test
    void handleConflictException_shouldReturnConflict() {
        // Given
        String traceId = "test-trace-789";
        MDC.put("trace.id", traceId);
        ConflictException exception = new ConflictException("Notification with reference DRAFT.IMP.2026.001 already exists");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleConflictException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Resource Conflict");
        assertThat(problemDetail.getDetail()).isEqualTo("Notification with reference DRAFT.IMP.2026.001 already exists");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/conflict"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleConflictException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        ConflictException exception = new ConflictException("Resource conflict");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleConflictException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    @Test
    void handleException_shouldReturnInternalServerError_forRuntimeException() {
        // Given
        String traceId = "test-trace-999";
        MDC.put("trace.id", traceId);
        RuntimeException exception = new RuntimeException("Unexpected database error");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problemDetail.getDetail()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/internal-error"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleException_shouldReturnInternalServerError_forIllegalStateException() {
        // Given
        IllegalStateException exception = new IllegalStateException("Invalid state");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleException_shouldReturnInternalServerError_forIllegalArgumentException() {
        // Given
        IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        RuntimeException exception = new RuntimeException("Error");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    private MethodArgumentNotValidException createValidationException(FieldError... fieldErrors) {
        try {
            // Create a real MethodParameter with an actual method to avoid NullPointerException
            Method testMethod = this.getClass().getDeclaredMethod("setUp");
            MethodParameter methodParameter = new MethodParameter(testMethod, -1);

            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldErrors));

            return new MethodArgumentNotValidException(methodParameter, bindingResult);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to create test MethodParameter", e);
        }
    }
}
