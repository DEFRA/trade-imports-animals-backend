package uk.gov.defra.trade.imports.animals.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

public final class CapturedLogs {

  private CapturedLogs() {}

  public static List<ILoggingEvent> loggedBy(Class<?> loggerOwner, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
    ListAppender<ILoggingEvent> appender =
        new ListAppender<>() {
          @Override
          protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list;
  }

  public static List<ILoggingEvent> loggedBy(Class<?> loggerOwner, Level level, Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
    Level configuredLevel = logger.getLevel();
    logger.setLevel(level);
    try {
      return loggedBy(loggerOwner, action);
    } finally {
      logger.setLevel(configuredLevel);
    }
  }

  public static ILoggingEvent assertOneLine(
      List<ILoggingEvent> lines, Level level, String formattedMessage) {
    assertThat(lines).hasSize(1);
    ILoggingEvent line = lines.getFirst();
    assertThat(line.getLevel()).isEqualTo(level);
    assertThat(line.getFormattedMessage()).isEqualTo(formattedMessage);
    return line;
  }

  public static List<String> messagesOf(List<ILoggingEvent> lines) {
    return lines.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  public static List<String> messagesAt(Level level, List<ILoggingEvent> lines) {
    return messagesOf(lines.stream().filter(line -> line.getLevel() == level).toList());
  }

  public static Map<String, Object> keyValues(ILoggingEvent event) {
    Map<String, Object> keyValues = new LinkedHashMap<>();
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs != null) {
      pairs.forEach(pair -> keyValues.put(pair.key, pair.value));
    }
    return keyValues;
  }
}
