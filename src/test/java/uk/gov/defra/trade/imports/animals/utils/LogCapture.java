package uk.gov.defra.trade.imports.animals.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogCapture {

    private LogCapture() {
    }

    public static List<ILoggingEvent> logEventsDuring(Class<?> loggingClass, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggingClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    public static List<ILoggingEvent> logEventsDuring(
        Class<?> loggingClass, Level level, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggingClass);
        Level configuredLevel = logger.getLevel();
        logger.setLevel(level);
        try {
            return logEventsDuring(loggingClass, action);
        } finally {
            logger.setLevel(configuredLevel);
        }
    }
}
