package uk.gov.defra.trade.imports.animals.filter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Logback filter that excludes health check endpoint requests from logs to reduce noise.
 * Checks if the MDC contains a url.full field that includes "/health".
 */
public class HealthCheckFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event.getMDCPropertyMap() == null) {
            return FilterReply.NEUTRAL;
        }

        String url = event.getMDCPropertyMap().get("url.full");
        if (url != null && url.contains("/health")) {
            return FilterReply.DENY;
        }

        return FilterReply.NEUTRAL;
    }
}
