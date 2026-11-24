package uk.gov.defra.trade.imports.animals.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.elastic.logging.JsonUtils;
import co.elastic.logging.logback.EcsEncoder;
import java.util.List;
import org.slf4j.event.KeyValuePair;

/**
 * The ECS encoder, plus a nested {@code event} object built from the line's {@code event.*}
 * key-value pairs. A {@link Number} value is written as a JSON number, and a non-finite one as a
 * string. The line's {@code error.*} key-value pairs are written as top-level fields, unless the
 * line carries a throwable, whose own ECS {@code error.*} fields are written instead. A line with
 * no {@code event.*} or {@code error.*} key-value pairs is written exactly as the ECS encoder
 * writes it.
 */
public class EcsEventEncoder extends EcsEncoder {

  private static final String EVENT_PREFIX = "event.";
  private static final String ERROR_PREFIX = "error.";

  @Override
  protected void addCustomFields(ILoggingEvent event, StringBuilder builder) {
    super.addCustomFields(event, builder);
    appendEventObject(fieldsStartingWith(EVENT_PREFIX, event), builder);
    if (event.getThrowableProxy() == null) {
      appendFlatFields(fieldsStartingWith(ERROR_PREFIX, event), builder);
    }
  }

  private static void appendEventObject(List<KeyValuePair> eventFields, StringBuilder builder) {
    if (eventFields.isEmpty()) {
      return;
    }
    builder.append("\"event\":{");
    for (KeyValuePair field : eventFields) {
      appendField(field.key.substring(EVENT_PREFIX.length()), field.value, builder);
    }
    builder.setLength(builder.length() - 1);
    builder.append("},");
  }

  private static void appendFlatFields(List<KeyValuePair> fields, StringBuilder builder) {
    for (KeyValuePair field : fields) {
      appendField(field.key, field.value, builder);
    }
  }

  private static void appendField(String key, Object value, StringBuilder builder) {
    builder.append('"');
    JsonUtils.quoteAsString(key, builder);
    builder.append("\":");
    appendValue(value, builder);
    builder.append(',');
  }

  private static List<KeyValuePair> fieldsStartingWith(String prefix, ILoggingEvent event) {
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs == null) {
      return List.of();
    }
    return pairs.stream().filter(pair -> pair.key.startsWith(prefix)).toList();
  }

  private static void appendValue(Object value, StringBuilder builder) {
    if (value == null) {
      builder.append("null");
    } else if (value instanceof Number number && isFinite(number)) {
      builder.append(number);
    } else {
      builder.append('"');
      JsonUtils.quoteAsString(value.toString(), builder);
      builder.append('"');
    }
  }

  private static boolean isFinite(Number number) {
    return switch (number) {
      case Double d -> Double.isFinite(d);
      case Float f -> Float.isFinite(f);
      default -> true;
    };
  }
}
