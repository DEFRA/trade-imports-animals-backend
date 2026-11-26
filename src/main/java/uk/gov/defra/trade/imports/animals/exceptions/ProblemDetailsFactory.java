package uk.gov.defra.trade.imports.animals.exceptions;

import static uk.gov.defra.trade.imports.animals.filter.RequestTracingFilter.MDC_TRACE_ID;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class ProblemDetailsFactory {

    private static final String PROPERTY_TRACE_ID = "traceId";

    private ProblemDetailsFactory() {
    }

    public static ProblemDetail problemDetail(
        HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(type);
        problemDetail.setTitle(title);
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null) {
            problemDetail.setProperty(PROPERTY_TRACE_ID, traceId);
        }
        return problemDetail;
    }
}
