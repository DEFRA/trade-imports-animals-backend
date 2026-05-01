package uk.gov.defra.trade.imports.animals.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    // The handler logs WARN/ERROR for every exception it handles — that's correct production
    // behaviour. In this unit test we only care about return values, so silence the logger to
    // avoid flooding the test output with expected error logs.
    //
    // NOTE: Mutating a static logger level in @BeforeEach/@AfterEach is intentional here.
    // These tests must run sequentially (JUnit 5 default) for the level mutation to be safe;
    // parallel execution would cause races between setUp/tearDown and test body logging.
    private static final Logger HANDLER_LOGGER =
        (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        MDC.clear();
        HANDLER_LOGGER.setLevel(Level.OFF);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        HANDLER_LOGGER.setLevel(null); // restore — inherit from root
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
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleValidationException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Validation Error");
        assertThat(problemDetail.getDetail()).isEqualTo("Validation failed for one or more fields");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/validation-error"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);

        @SuppressWarnings("unchecked")
        Map<String, List<String>> errors = (Map<String, List<String>>) problemDetail.getProperties().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get("origin")).containsExactly("must not be null");
        assertThat(errors.get("commodity")).containsExactly("must not be blank");
    }

    @Test
    void handleValidationException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        MethodArgumentNotValidException exception = createValidationException(
            new FieldError("notification", "origin", "must not be null")
        );

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleValidationException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        Map<String, Object> properties = problemDetail.getProperties();
        assertThat(properties).isNotNull();
        assertThat(properties).doesNotContainKey("traceId");
    }

    @Test
    void handleNotFoundException_shouldReturnNotFound() {
        // Given
        String traceId = "test-trace-456";
        MDC.put("trace.id", traceId);
        NotFoundException exception = new NotFoundException("Notification with id 12345 not found");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleNotFoundException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
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
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleNotFoundException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        Map<String, Object> properties = problemDetail.getProperties();
        assertThat(properties).satisfiesAnyOf(
            p -> assertThat(p).isNull(),
            p -> assertThat(p).doesNotContainKey("traceId")
        );
    }

    @Test
    void handleConflictException_shouldReturnConflict() {
        // Given
        String traceId = "test-trace-789";
        MDC.put("trace.id", traceId);
        ConflictException exception = new ConflictException("Notification with reference DRAFT.IMP.2026.001 already exists");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleConflictException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
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
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleConflictException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        Map<String, Object> properties = problemDetail.getProperties();
        assertThat(properties).satisfiesAnyOf(
            p -> assertThat(p).isNull(),
            p -> assertThat(p).doesNotContainKey("traceId")
        );
    }

    @Test
    void handleBadRequestException_shouldReturnBadRequestWithDetail() {
        // Given
        String traceId = "test-trace-bad-1";
        MDC.put("trace.id", traceId);
        BadRequestException exception =
            new BadRequestException("Scan callback missing required correlationId in metadata");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleBadRequestException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Bad Request");
        assertThat(problemDetail.getDetail())
            .isEqualTo("Scan callback missing required correlationId in metadata");
        assertThat(problemDetail.getType())
            .isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/bad-request"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleBadRequestException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        BadRequestException exception = new BadRequestException("Bad request");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleBadRequestException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        Map<String, Object> properties = problemDetail.getProperties();
        assertThat(properties).satisfiesAnyOf(
            p -> assertThat(p).isNull(),
            p -> assertThat(p).doesNotContainKey("traceId")
        );
    }

    @Test
    void handleException_shouldReturnInternalServerError_forRuntimeException() {
        // Given
        String traceId = "test-trace-999";
        MDC.put("trace.id", traceId);
        RuntimeException exception = new RuntimeException("Unexpected database error");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleException_shouldReturnInternalServerError_forIllegalArgumentException() {
        // Given
        IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        RuntimeException exception = new RuntimeException("Error");

        // When
        ResponseEntity<ProblemDetail> response = exceptionHandler.handleException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        Map<String, Object> properties = problemDetail.getProperties();
        assertThat(properties).satisfiesAnyOf(
            p -> assertThat(p).isNull(),
            p -> assertThat(p).doesNotContainKey("traceId")
        );
    }

    @Test
    void handleServiceUnavailableException_shouldReturnBadGateway() {
        // Given
        String traceId = "test-trace-svc-unavail";
        MDC.put("trace.id", traceId);
        ServiceUnavailableException exception =
            new ServiceUnavailableException("cdp-uploader returned an error response: HTTP 503");

        // When
        ResponseEntity<ProblemDetail> response =
            exceptionHandler.handleServiceUnavailableException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Upstream Service Error");
        assertThat(problemDetail.getDetail())
            .isEqualTo("cdp-uploader returned an error response: HTTP 503");
        assertThat(problemDetail.getType())
            .isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/upstream-error"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleServiceUnavailableException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        ServiceUnavailableException exception =
            new ServiceUnavailableException("cdp-uploader returned an error response: HTTP 503");

        // When
        ResponseEntity<ProblemDetail> response =
            exceptionHandler.handleServiceUnavailableException(exception);
        ProblemDetail problemDetail = response.getBody();

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        Map<String, Object> properties = problemDetail.getProperties();
        assertThat(properties).satisfiesAnyOf(
            p -> assertThat(p).isNull(),
            p -> assertThat(p).doesNotContainKey("traceId")
        );
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
