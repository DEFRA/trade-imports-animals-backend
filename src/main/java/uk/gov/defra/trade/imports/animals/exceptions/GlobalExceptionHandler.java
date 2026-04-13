package uk.gov.defra.trade.imports.animals.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST API error responses.
 *
 * Uses Spring 6 ProblemDetail (RFC 7807) for standardized error responses.
 * Includes request trace ID in error responses for log correlation.
 *
 * CDP Compliance:
 * - Structured error responses with trace ID
 * - Proper HTTP status codes (400, 404, 409, 500)
 * - Validation errors with field-level details
 * - Logs errors with trace ID for troubleshooting
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String MDC_TRACE_ID = "trace.id";

    /**
     * Handle validation errors (400 Bad Request).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Validation error (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Validation failed for one or more fields"
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/validation-error"));
        problemDetail.setTitle("Validation Error");

        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }

        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        problemDetail.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle not found errors (404 Not Found).
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFoundException(NotFoundException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Resource not found (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/not-found"));
        problemDetail.setTitle("Resource Not Found");

        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle conflict errors (409 Conflict).
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflictException(ConflictException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Resource conflict (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/conflict"));
        problemDetail.setTitle("Resource Conflict");

        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle unexpected errors (500 Internal Server Error).
     *
     * Note: Does NOT catch Spring framework exceptions like NoResourceFoundException
     * (404) or other HTTP-related exceptions. Only catches application-level exceptions.
     * This allows Spring to handle its own exceptions appropriately (e.g., 404 for
     * missing endpoints).
     */
    @ExceptionHandler({
        RuntimeException.class,
        IllegalStateException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ProblemDetail> handleException(Exception ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.error("Unexpected error (trace: {}): {}", traceId, ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/internal-error"));
        problemDetail.setTitle("Internal Server Error");

        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }
}
