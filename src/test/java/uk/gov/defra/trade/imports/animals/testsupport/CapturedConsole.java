package uk.gov.defra.trade.imports.animals.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class CapturedConsole implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  private CapturedConsole() {
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
  }

  public static CapturedConsole captureSystemOut() {
    return new CapturedConsole();
  }

  public String text() {
    return captured.toString(StandardCharsets.UTF_8);
  }

  public List<JsonNode> jsonLines() {
    return Arrays.stream(text().split("\n"))
        .map(String::trim)
        .filter(line -> line.startsWith("{"))
        .map(CapturedConsole::parse)
        .toList();
  }

  private static JsonNode parse(String line) {
    try {
      return JSON.readTree(line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    System.setOut(originalOut);
  }
}
