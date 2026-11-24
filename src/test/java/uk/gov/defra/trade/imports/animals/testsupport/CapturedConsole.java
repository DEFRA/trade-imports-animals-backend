package uk.gov.defra.trade.imports.animals.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class CapturedConsole implements AutoCloseable {

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

  @Override
  public void close() {
    System.setOut(originalOut);
  }
}
