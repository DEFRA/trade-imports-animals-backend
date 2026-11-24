package uk.gov.defra.trade.imports.animals.metrics;

import java.util.ArrayList;
import java.util.List;

final class RecordedValues {

  private List<Double> values = new ArrayList<>();

  synchronized void add(double value) {
    values.add(value);
  }

  synchronized List<Double> takeAll() {
    List<Double> taken = values;
    values = new ArrayList<>();
    return taken;
  }
}
