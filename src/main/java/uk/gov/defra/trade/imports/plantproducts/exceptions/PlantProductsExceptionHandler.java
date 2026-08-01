package uk.gov.defra.trade.imports.plantproducts.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "uk.gov.defra.trade.imports.plantproducts")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class PlantProductsExceptionHandler {

    private static final String MDC_TRACE_ID = "trace.id";

    @ExceptionHandler(PlantProductsBadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequestException(PlantProductsBadRequestException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Bad request (trace: {}): {}", traceId, ex.getMessage());
        return respond(problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "bad-request", "Bad Request", traceId));
    }

    @ExceptionHandler(PlantProductsNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFoundException(PlantProductsNotFoundException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Resource not found (trace: {}): {}", traceId, ex.getMessage());
        return respond(problem(HttpStatus.NOT_FOUND, ex.getMessage(), "not-found", "Resource Not Found", traceId));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolationException(ConstraintViolationException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Constraint violation (trace: {}): {}", traceId, ex.getMessage());
        ProblemDetail problemDetail = problem(HttpStatus.BAD_REQUEST,
            "Validation failed for one or more fields", "validation-error", "Validation Error", traceId);
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String propertyPath = violation.getPropertyPath().toString();
            String field = propertyPath.contains(".")
                ? propertyPath.substring(propertyPath.lastIndexOf('.') + 1)
                : propertyPath;
            errors.computeIfAbsent(field, k -> new ArrayList<>()).add(violation.getMessage());
        }
        problemDetail.setProperty("errors", errors);
        return respond(problemDetail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Validation error (trace: {}): {}", traceId, ex.getMessage());
        ProblemDetail problemDetail = problem(HttpStatus.BAD_REQUEST,
            "Validation failed for one or more fields", "validation-error", "Validation Error", traceId);
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.computeIfAbsent(error.getField(), k -> new ArrayList<>()).add(error.getDefaultMessage());
        }
        problemDetail.setProperty("errors", errors);
        return respond(problemDetail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableRequestBody(HttpMessageNotReadableException ex) {
        String traceId = MDC.get(MDC_TRACE_ID);
        log.warn("Unreadable request body (trace: {}): {}", traceId, ex.getMessage());
        return respond(problem(HttpStatus.BAD_REQUEST, "Request body is missing or malformed",
            "bad-request", "Bad Request", traceId));
    }

    private static ProblemDetail problem(
        HttpStatus status, String detail, String typeSlug, String title, String traceId) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/" + typeSlug));
        problemDetail.setTitle(title);
        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }
        return problemDetail;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problemDetail) {
        return ResponseEntity.status(problemDetail.getStatus())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }
}
