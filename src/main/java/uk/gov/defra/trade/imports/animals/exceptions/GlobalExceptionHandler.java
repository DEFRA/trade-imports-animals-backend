package uk.gov.defra.trade.imports.animals.exceptions;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler producing RFC 7807 ProblemDetail responses with trace IDs.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String MDC_TRACE_ID = "trace.id";
    private static final String PROPERTY_TRACE_ID = "traceId";
    private static final String PROPERTY_ERRORS = "errors";
    private static final String TITLE_VALIDATION_ERROR = "Validation Error";
    /**
     * RFC 7807 {@code type} for the field-validation 400s — {@link #handleValidationException} and
     * {@link #handleConstraintViolationException} — each of which carries an {@code errors} map
     * naming the offending fields.
     */
    private static final URI TYPE_VALIDATION_ERROR =
        URI.create("https://api.cdp.defra.cloud/problems/validation-error");
    /**
     * RFC 7807 {@code type} for a body the parser could not read at all. A problem type of its own,
     * not field validation: nothing bound, so there is no {@code errors} map to name fields in.
     */
    private static final URI TYPE_MALFORMED_REQUEST =
        URI.create("https://api.cdp.defra.cloud/problems/malformed-request");

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

        problemDetail.setType(TYPE_VALIDATION_ERROR);
        problemDetail.setTitle(TITLE_VALIDATION_ERROR);

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.computeIfAbsent(error.getField(), k -> new ArrayList<>()).add(error.getDefaultMessage());
        }
        problemDetail.setProperty(PROPERTY_ERRORS, errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle an unparseable request body (400 Bad Request) — malformed JSON, or a value that does
     * not fit the field's type.
     *
     * <p>Without this the exception reaches the {@code RuntimeException} catch-all and the caller
     * is told 500, blaming the server for the caller's payload. EUDPA-565 made that reachable in
     * an ordinary way: every date on this API is now an {@code Instant}, so a date-only
     * {@code "2026-12-12"} where {@code "2026-12-12T00:00:00Z"} is required lands here rather
     * than binding.
     *
     * <p>The parser message is logged but deliberately not returned — it quotes the submitted
     * value and names internal types.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableRequestBody(HttpMessageNotReadableException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Unreadable request body (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Request body could not be read. Check the JSON is well-formed and that each date is "
                + "an RFC 3339 instant, for example 2026-12-12T00:00:00Z"
        );

        problemDetail.setType(TYPE_MALFORMED_REQUEST);
        problemDetail.setTitle("Malformed Request");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle method-parameter validation errors from {@code @Validated} controllers (400 Bad Request).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolationException(
        ConstraintViolationException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Constraint violation (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Validation failed for one or more fields"
        );

        problemDetail.setType(TYPE_VALIDATION_ERROR);
        problemDetail.setTitle(TITLE_VALIDATION_ERROR);

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String propertyPath = violation.getPropertyPath().toString();
            String field = propertyPath.contains(".")
                ? propertyPath.substring(propertyPath.lastIndexOf('.') + 1)
                : propertyPath;
            errors.computeIfAbsent(field, k -> new ArrayList<>()).add(violation.getMessage());
        }
        problemDetail.setProperty(PROPERTY_ERRORS, errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle application-level bad-request errors (400 Bad Request).
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequestException(BadRequestException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Bad request (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/bad-request"));
        problemDetail.setTitle("Bad Request");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

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
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle upstream service errors (502 Bad Gateway).
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleServiceUnavailableException(ServiceUnavailableException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.error("Upstream service error (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY,
            ex.getMessage()
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/upstream-error"));
        problemDetail.setTitle("Upstream Service Error");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
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
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle optimistic-locking failures from Spring Data (409 Conflict, code {@code STALE_CONCURRENCY_TOKEN}).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
        OptimisticLockingFailureException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Stale-concurrencyToken conflict (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "The record was modified by another request; refresh and try again."
        );
        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/stale-concurrency-token"));
        problemDetail.setTitle("Stale Concurrency Token");
        problemDetail.setProperty("code", "STALE_CONCURRENCY_TOKEN");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle oversized multipart uploads (413 Payload Too Large).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Upload size exceeded (trace: {}): {}", traceId, ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Uploaded file exceeds the maximum permitted size"
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/payload-too-large"));
        problemDetail.setTitle("Payload Too Large");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }

    /**
     * Handle outbox write failures (500 Internal Server Error).
     *
     * Logs full diagnostic detail server-side; returns a generic message to the client
     * so no internal details (lock keys, mongo error codes, stack traces) are leaked.
     */
    @ExceptionHandler(OutboxWriteException.class)
    public ResponseEntity<ProblemDetail> handleOutboxWriteException(OutboxWriteException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.error("Outbox write failed (trace: {}): aggregateId={} attemptedVersion={} correlationId={} cause={}",
            traceId, ex.getAggregateId(), ex.getAggregateVersion(), ex.getCorrelationId(),
            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An error occurred during submission. Please try again."
        );
        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/internal-error"));
        problemDetail.setTitle("Internal Server Error");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
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
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> handleException(RuntimeException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.error("Unexpected error (trace: {}): {}", traceId, ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."
        );

        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/internal-error"));
        problemDetail.setTitle("Internal Server Error");

        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }
}
